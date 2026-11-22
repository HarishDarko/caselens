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
    private final TicketRepository tickets;
    private final OutboxEventRepository events;
    private final TriageJobRepository jobs;
    private final TriageResultRepository results;
    private final OutboxPublisher publisher;
    private final Clock clock;
    private final OutboxPublicationService publicationService;
    private final TriageAttemptRepository attempts;
    private final TriageMetrics metrics;

    public TriageRequestService(TicketRepository tickets, OutboxEventRepository events, TriageJobRepository jobs,
            OutboxPublisher publisher, Clock clock) {
        this(tickets, events, jobs, null, publisher, clock, null, null, TriageMetrics.noop());
    }

    public TriageRequestService(TicketRepository tickets, OutboxEventRepository events, TriageJobRepository jobs,
            TriageResultRepository results, OutboxPublisher publisher, Clock clock) {
        this(tickets, events, jobs, results, publisher, clock, null, null, TriageMetrics.noop());
    }

    @Autowired
    public TriageRequestService(TicketRepository tickets, OutboxEventRepository events, TriageJobRepository jobs,
            TriageResultRepository results, OutboxPublisher publisher, Clock clock,
            OutboxPublicationService publicationService, TriageAttemptRepository attempts, TriageMetrics metrics) {
        this.tickets = tickets;
        this.events = events;
        this.jobs = jobs;
        this.results = results;
        this.publisher = publisher;
        this.clock = clock;
        this.publicationService = publicationService;
        this.attempts = attempts;
        this.metrics = metrics;
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
     * Reviewer recovery. Only FailureRecoveryService.retry calls this, after it has checked that the failed job is
     * one SQS no longer owns (terminal, or the acknowledged synthetic demo failure). A replacement is queued only
     * while that failed job is still the ticket's latest job; otherwise the latest job is returned unchanged. The
     * ticket row lock serializes this with request(), so a double click queues one replacement, and an old failure
     * can never be replayed past a newer job that SQS still owns.
     */
    @Transactional
    TriageJobSnapshot replay(UUID workspaceId, UUID ticketId, UUID failedJobId, String correlationId) {
        metrics.requested();
        String safeCorrelationId = CorrelationIds.normalize(correlationId);
        Ticket ticket = tickets.findByIdAndWorkspaceIdForUpdate(ticketId, workspaceId)
                .orElseThrow(TicketNotFoundException::new);
        TriageJob latest = latestJob(workspaceId, ticketId).orElseThrow(TicketNotFoundException::new);
        boolean failed = latest.getStatus() == TriageJobStatus.TERMINAL_FAILURE
                || latest.getStatus() == TriageJobStatus.RETRYABLE_FAILURE;
        if (!latest.getId().equals(failedJobId) || !failed) return latest.snapshot();
        return enqueue(ticket, workspaceId, ticketId, safeCorrelationId, latest.getId());
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
            persistAfterCommit(event);
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

    @Transactional(readOnly = true)
    public TriageProcessingSnapshot processing(UUID workspaceId, UUID ticketId) {
        tickets.findByIdAndWorkspaceId(ticketId, workspaceId).orElseThrow(TicketNotFoundException::new);
        TriageJob job = jobs.findFirstByTicketIdAndWorkspaceIdAndContentVersionOrderByCreatedAtDesc(
                ticketId, workspaceId, 1)
                .orElseThrow(TicketNotFoundException::new);
        List<TriageAttemptSnapshot> timeline = attempts == null ? List.of()
                : attempts.findByJobIdOrderByAttemptNumberAsc(job.getId()).stream()
                        .map(TriageAttempt::snapshot).toList();
        return new TriageProcessingSnapshot(job.snapshot(), results.findByEventId(job.getEventId()).orElse(null), timeline);
    }
}
