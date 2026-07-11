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

    @Transactional
    public TriageJobSnapshot request(UUID workspaceId, UUID ticketId, String correlationId) {
        metrics.requested();
        String safeCorrelationId = CorrelationIds.normalize(correlationId);
        Ticket ticket = tickets.findByIdAndWorkspaceIdForUpdate(ticketId, workspaceId)
                .orElseThrow(TicketNotFoundException::new);
        int contentVersion = 1;
        var existing = jobs.findFirstByTicketIdAndWorkspaceIdAndContentVersionOrderByCreatedAtDesc(
                ticketId, workspaceId, contentVersion);
        if (existing.isPresent()
                && existing.get().getStatus() != TriageJobStatus.TERMINAL_FAILURE
                && existing.get().getStatus() != TriageJobStatus.RETRYABLE_FAILURE) {
            return existing.get().snapshot();
        }

        Instant now = clock.instant();
        ticket.queue(now);
        tickets.save(ticket);
        OutboxEvent event = events.save(OutboxEvent.create(workspaceId, ticketId, safeCorrelationId, now));
        TriageJob job = TriageJob.queued(UUID.randomUUID(), event.getId(), workspaceId, ticketId, contentVersion, now);
        if (existing.isPresent()) job.setReplayedFromJobId(existing.get().getId());
        job = jobs.save(job);
        publishAfterCommit(event);
        log.info("triage.requested correlationId={} workspaceId={} ticketId={} jobId={} eventId={}",
                safeCorrelationId, workspaceId, ticketId, job.getId(), event.getId());
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
