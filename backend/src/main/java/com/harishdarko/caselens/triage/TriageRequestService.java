package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketNotFoundException;
import com.harishdarko.caselens.ticket.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class TriageRequestService {
    private static final Logger log = LoggerFactory.getLogger(TriageRequestService.class);
    private static final int CONTENT_VERSION = 1;
    static final String SQS_DELIVERY_EXHAUSTED = "SQS_DELIVERY_EXHAUSTED";
    static final String STRANDED_UNPUBLISHED = "STRANDED_UNPUBLISHED";
    private final TicketRepository tickets;
    private final OutboxEventRepository events;
    private final TriageJobRepository jobs;
    private final TriageResultRepository results;
    private final OutboxPublisher publisher;
    private final Clock clock;
    private final OutboxPublicationService publicationService;
    private final TriageAttemptRepository attempts;
    private final TriageMetrics metrics;
    private final ModelInvocationRepository invocations;

    public TriageRequestService(TicketRepository tickets, OutboxEventRepository events, TriageJobRepository jobs,
            OutboxPublisher publisher, Clock clock) {
        this(tickets, events, jobs, null, publisher, clock, null, null, TriageMetrics.noop(), null);
    }

    public TriageRequestService(TicketRepository tickets, OutboxEventRepository events, TriageJobRepository jobs,
            TriageResultRepository results, OutboxPublisher publisher, Clock clock) {
        this(tickets, events, jobs, results, publisher, clock, null, null, TriageMetrics.noop(), null);
    }

    @Autowired
    public TriageRequestService(TicketRepository tickets, OutboxEventRepository events, TriageJobRepository jobs,
            TriageResultRepository results, OutboxPublisher publisher, Clock clock,
            OutboxPublicationService publicationService, TriageAttemptRepository attempts, TriageMetrics metrics,
            ModelInvocationRepository invocations) {
        this.tickets = tickets;
        this.events = events;
        this.jobs = jobs;
        this.results = results;
        this.publisher = publisher;
        this.clock = clock;
        this.publicationService = publicationService;
        this.attempts = attempts;
        this.metrics = metrics;
        this.invocations = invocations;
    }

    /**
     * The triage button. It returns the ticket's latest job unless that job ended in TERMINAL_FAILURE. A job in
     * RETRYABLE_FAILURE is returned as it is: SQS still owns its message and redelivers it, so queuing a second job
     * here would make two workers triage the same ticket (or collide on the active-job index when the old message
     * comes back). Reviewer recovery of an acknowledged failure goes through FailureRecoveryService.retry instead.
     */
    @Transactional
    public TriageJobSnapshot request(UUID workspaceId, UUID ticketId, String correlationId) {
        metrics.requested();
        String safeCorrelationId = CorrelationIds.normalize(correlationId);
        Ticket ticket = tickets.findByIdAndWorkspaceIdForUpdate(ticketId, workspaceId)
                .orElseThrow(TicketNotFoundException::new);
        var existing = latestJob(workspaceId, ticketId);
        if (existing.isPresent() && existing.get().getStatus() != TriageJobStatus.TERMINAL_FAILURE) {
            return existing.get().snapshot();
        }
        return enqueue(ticket, workspaceId, ticketId, safeCorrelationId, existing.map(TriageJob::getId).orElse(null));
    }

    /**
     * Reviewer recovery. Only FailureRecoveryService.retry calls this. Under the ticket lock it reads the failed job
     * and checks that SQS no longer owns it (FailureRecoveryService.requireReplayable: terminal, the acknowledged
     * synthetic demo failure, a retryable failure past the redelivery window, or a stranded QUEUED or PROCESSING job
     * whose message went to the DLQ). Every writer of a ticket's jobs takes this lock first (request, replay, and the
     * worker's claim and final writes), so what is read here stays true until this transaction commits. A
     * replacement is queued only while that job is still the ticket's latest job; otherwise the latest job is
     * returned unchanged. So a double click queues one replacement, and an old failure can never be replayed past a
     * newer job that SQS still owns. A stranded job is first closed as TERMINAL_FAILURE (SQS_DELIVERY_EXHAUSTED, or
     * STRANDED_UNPUBLISHED when its message was never published) by a conditional update, so a late, relayed or
     * redriven original message is acknowledged as a duplicate before any provider call.
     */
    @Transactional
    TriageJobSnapshot replay(UUID workspaceId, UUID ticketId, UUID failedJobId, String correlationId) {
        String safeCorrelationId = CorrelationIds.normalize(correlationId);
        Ticket ticket = tickets.findByIdAndWorkspaceIdForUpdate(ticketId, workspaceId)
                .orElseThrow(TicketNotFoundException::new);
        TriageJob failedJob = jobs.findByIdAndWorkspaceId(failedJobId, workspaceId)
                .filter(job -> job.getTicketId().equals(ticketId))
                .orElseThrow(TicketNotFoundException::new);
        FailureRecoveryService.requireReplayable(failedJob, clock.instant());
        metrics.requested();
        TriageJob latest = latestJob(workspaceId, ticketId).orElseThrow(TicketNotFoundException::new);
        if (!latest.getId().equals(failedJobId)) return latest.snapshot();
        boolean failed = latest.getStatus() == TriageJobStatus.TERMINAL_FAILURE
                || latest.getStatus() == TriageJobStatus.RETRYABLE_FAILURE;
        if (failed) return enqueue(ticket, workspaceId, ticketId, safeCorrelationId, latest.getId());
        if (!closeStranded(latest, safeCorrelationId)) return latest.snapshot();
        return enqueue(ticket, workspaceId, ticketId, safeCorrelationId, latest.getId());
    }

    /**
     * Closes a stranded QUEUED or PROCESSING job and its open attempts. Returns false, changing nothing, when the job
     * is not stranded at the moment its row is locked (a worker claimed it, or it moved on).
     */
    private boolean closeStranded(TriageJob job, String correlationId) {
        if (job.getStatus() != TriageJobStatus.QUEUED && job.getStatus() != TriageJobStatus.PROCESSING) return false;
        Instant now = clock.instant();
        String errorCode = strandedErrorCode(job);
        int closed = jobs.closeStranded(job.getId(), errorCode, now,
                now.minus(FailureRecoveryService.SQS_REDELIVERY_WINDOW), now.minus(TriageJobRepository.CLAIM_LEASE));
        if (closed != 1) return false;
        if (attempts != null) {
            for (TriageAttempt attempt : attempts.findByJobIdOrderByAttemptNumberAsc(job.getId())) {
                if (attempt.getStatus() != TriageAttemptStatus.PROCESSING) continue;
                attempt.fail(TriageAttemptStatus.TERMINAL_FAILURE, errorCode, now);
                attempts.save(attempt);
            }
        }
        metrics.terminalFailure();
        log.warn("triage.stranded_job_closed correlationId={} workspaceId={} ticketId={} jobId={} eventId={} status={} errorCode={}",
                correlationId, job.getWorkspaceId(), job.getTicketId(), job.getId(), job.getEventId(), job.getStatus(),
                errorCode);
        return true;
    }

    /**
     * Why a stranded job is closed. SQS_DELIVERY_EXHAUSTED means its message was published and SQS gave up on it
     * (it is in the DLQ). STRANDED_UNPUBLISHED means the first publish failed and the outbox relay (every 30 minutes
     * in production) had not sent it yet, so the message never reached SQS. If the relay sends it later, the worker
     * acknowledges it as a duplicate of the closed job.
     */
    private String strandedErrorCode(TriageJob job) {
        boolean published = events.findById(job.getEventId()).map(event -> event.getPublishedAt() != null).orElse(true);
        return published ? SQS_DELIVERY_EXHAUSTED : STRANDED_UNPUBLISHED;
    }

    private java.util.Optional<TriageJob> latestJob(UUID workspaceId, UUID ticketId) {
        return jobs.findFirstByTicketIdAndWorkspaceIdAndContentVersionOrderByCreatedAtDesc(ticketId, workspaceId, CONTENT_VERSION);
    }

    private TriageJobSnapshot enqueue(Ticket ticket, UUID workspaceId, UUID ticketId, String safeCorrelationId,
            UUID replayedFromJobId) {
        Instant now = clock.instant();
        ticket.queue(now);
        tickets.save(ticket);
        OutboxEvent event = events.save(OutboxEvent.create(workspaceId, ticketId, safeCorrelationId, now));
        TriageJob job = TriageJob.queued(UUID.randomUUID(), event.getId(), workspaceId, ticketId, CONTENT_VERSION, now);
        if (replayedFromJobId != null) job.setReplayedFromJobId(replayedFromJobId);
        job = jobs.save(job);
        publishAfterCommit(event);
        log.info("triage.requested correlationId={} workspaceId={} ticketId={} jobId={} eventId={} replayedFromJobId={}",
                safeCorrelationId, workspaceId, ticketId, job.getId(), event.getId(), replayedFromJobId);
        return job.snapshot();
    }

    private void publishAfterCommit(OutboxEvent event) {
        Runnable publish = () -> {
            try {
                publisher.publish(event);
                event.markPublished(clock.instant());
                metrics.outboxPublished();
            } catch (QueuePublishException failure) {
                event.markPublishFailure(failure.errorCode());
                metrics.outboxFailed();
                log.warn("triage.outbox_publish_failed eventId={} errorCode={}", event.getId(), failure.errorCode());
            } catch (RuntimeException failure) {
                event.markPublishFailure("QUEUE_TRANSIENT");
                metrics.outboxFailed();
                log.warn("triage.outbox_publish_failed eventId={} errorCode=QUEUE_TRANSIENT", event.getId());
            }
            try {
                persistAfterCommit(event);
            } catch (RuntimeException failure) {
                // The job and its outbox row committed already, so the caller gets the queued job, not a 500. The
                // relay reconciles the outbox row (it republishes rows without published_at, and the worker treats
                // a second delivery of a finished job as a duplicate). Only the exception type is logged.
                log.warn("triage.outbox_persist_failed eventId={} error={}", event.getId(), failure.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else {
            publish.run();
        }
    }

    private void persistAfterCommit(OutboxEvent event) {
        if (publicationService == null) {
            events.save(event);
            return;
        }
        publicationService.persist(event);
    }

    /**
     * The ticket's latest job with its attempts and result. A ticket that has never been queued but has a recorded
     * result answers with no job and that result; a ticket with neither is not found, as before.
     */
    @Transactional(readOnly = true)
    public TriageProcessingSnapshot processing(UUID workspaceId, UUID ticketId) {
        tickets.findByIdAndWorkspaceId(ticketId, workspaceId).orElseThrow(TicketNotFoundException::new);
        TriageJob job = jobs.findFirstByTicketIdAndWorkspaceIdAndContentVersionOrderByCreatedAtDesc(
                ticketId, workspaceId, 1).orElse(null);
        if (job == null) {
            TriageResult recorded = results == null ? null
                    : results.findFirstByTicketIdAndWorkspaceIdOrderByCreatedAtDescIdDesc(ticketId, workspaceId)
                            .filter(TriageResult::isRecorded).orElseThrow(TicketNotFoundException::new);
            if (recorded == null) throw new TicketNotFoundException();
            return new TriageProcessingSnapshot(null, recorded, List.of(), fallbackReason(null, recorded));
        }
        List<TriageAttemptSnapshot> timeline = attempts == null ? List.of()
                : attempts.findByJobIdOrderByAttemptNumberAsc(job.getId()).stream()
                        .map(TriageAttempt::snapshot).toList();
        TriageResult result = results.findByEventId(job.getEventId()).orElse(null);
        return new TriageProcessingSnapshot(job.snapshot(), result, timeline, fallbackReason(job, result));
    }

    /**
     * Why the rules decided this job's result, read back from the failed provider call the engine recorded for it (its
     * sanitized error code), so no column is needed and results saved before this field existed get a reason too. A
     * fallback whose call was not recorded (the recorder is best effort) reads as PROVIDER_UNAVAILABLE.
     */
    private FallbackReason fallbackReason(TriageJob job, TriageResult result) {
        if (result == null || result.getDecisionSource() != DecisionSource.RULES_FALLBACK) return null;
        if (invocations == null || job == null) return FallbackReason.PROVIDER_UNAVAILABLE;
        ModelInvocation latest = new JobInvocations(invocations.findByTicketIds(List.of(job.getTicketId()))).latestFor(job);
        return FallbackReason.fromInvocation(latest);
    }
}
