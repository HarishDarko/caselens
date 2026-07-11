package com.harishdarko.caselens.triage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harishdarko.caselens.ticket.Ticket;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TriageWorker {
    private static final Logger log = LoggerFactory.getLogger(TriageWorker.class);
    private final TriageJobRepository jobs;
    private final com.harishdarko.caselens.ticket.TicketRepository tickets;
    private final TriageResultRepository results;
    private final TriageAttemptRepository attempts;
    private final TriageEngine engine;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TriageMetrics metrics;
    private final TriageFailureClassifier classifier = new TriageFailureClassifier();

    public TriageWorker(TriageJobRepository jobs, com.harishdarko.caselens.ticket.TicketRepository tickets,
            TriageResultRepository results, TriageEngine engine, ObjectMapper mapper, Clock clock) {
        this(jobs, tickets, results, null, engine, mapper, clock, TriageMetrics.noop());
    }

    public TriageWorker(TriageJobRepository jobs, com.harishdarko.caselens.ticket.TicketRepository tickets,
            TriageResultRepository results, TriageAttemptRepository attempts, TriageEngine engine,
            ObjectMapper mapper, Clock clock, TriageMetrics metrics) {
        this.jobs = Objects.requireNonNull(jobs);
        this.tickets = Objects.requireNonNull(tickets);
        this.results = Objects.requireNonNull(results);
        this.attempts = attempts;
        this.engine = Objects.requireNonNull(engine);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @org.springframework.transaction.annotation.Transactional
    public WorkerResult process(QueueMessage message) {
        Timer.Sample latency = metrics.startLatency();
        try {
            return processInternal(message);
        } finally {
            metrics.stopLatency(latency);
        }
    }

    private WorkerResult processInternal(QueueMessage message) {
        TriageJob job = jobs.findByEventId(message.eventId()).orElse(null);
        if (job == null) {
            metrics.terminalFailure();
            return WorkerResult.terminal("INVALID_EVENT");
        }
        if (job.getStatus() == TriageJobStatus.COMPLETED) {
            metrics.duplicateDelivery();
            return WorkerResult.duplicate();
        }
        if (!job.getWorkspaceId().equals(message.workspaceId()) || !job.getTicketId().equals(message.ticketId())) {
            job.terminalFailure("INVALID_EVENT", clock.instant());
            jobs.save(job);
            metrics.terminalFailure();
            log.warn("triage.invalid_event eventId={} jobId={} workspaceId={} ticketId={}",
                    message.eventId(), job.getId(), message.workspaceId(), message.ticketId());
            return WorkerResult.terminal("INVALID_EVENT");
        }
        if (jobs.claimForProcessing(message.eventId(), clock.instant()) != 1) {
            metrics.duplicateDelivery();
            return WorkerResult.duplicate();
        }
        job = jobs.findByEventId(message.eventId()).orElse(null);
        if (job == null) {
            metrics.terminalFailure();
            return WorkerResult.terminal("INVALID_EVENT");
        }
        TriageAttempt attempt = attempts == null ? null
                : attempts.save(TriageAttempt.processing(job.getId(), job.getAttemptCount(), clock.instant()));

        Ticket ticket = tickets.findByIdAndWorkspaceId(message.ticketId(), message.workspaceId()).orElse(null);
        if (ticket == null) {
            job.terminalFailure("TICKET_NOT_FOUND", clock.instant());
            jobs.save(job);
            if (attempt != null) {
                attempt.fail(TriageAttemptStatus.TERMINAL_FAILURE, "TICKET_NOT_FOUND", clock.instant());
                attempts.save(attempt);
            }
            metrics.terminalFailure();
            return WorkerResult.terminal("TICKET_NOT_FOUND");
        }
        ticket.processing(clock.instant());
        tickets.save(ticket);
        try {
            TriageDecision decision = engine.triage(new TriageRequest(ticket.getId(), ticket.getSubject(), ticket.getMessage(),
                    ticket.getChannel(), 0));
            results.save(TriageResult.from(job.getEventId(), job.getWorkspaceId(), decision, clock.instant(), mapper));
            job.complete(clock.instant());
            ticket.completed(clock.instant());
            jobs.save(job);
            tickets.save(ticket);
            if (attempt != null) {
                attempt.complete(clock.instant());
                attempts.save(attempt);
            }
            metrics.completed();
            if (decision.decisionSource() == DecisionSource.RULES_FALLBACK) metrics.fallback();
            log.info("triage.completed correlationId={} workspaceId={} ticketId={} jobId={} eventId={} source={}",
                    message.correlationId(), message.workspaceId(), message.ticketId(), job.getId(),
                    message.eventId(), decision.decisionSource());
            return WorkerResult.ack();
        } catch (RuntimeException failure) {
            FailureClassification classification = classifier.classify(failure);
            if (classification.retryable()) {
                job.retryableFailure(classification.errorCode(), clock.instant());
                ticket.queue(clock.instant());
                jobs.save(job);
                tickets.save(ticket);
                if (attempt != null) {
                    attempt.fail(TriageAttemptStatus.RETRYABLE_FAILURE, classification.errorCode(), clock.instant());
                    attempts.save(attempt);
                }
                metrics.retryableFailure();
                log.warn("triage.retryable_failure correlationId={} workspaceId={} ticketId={} jobId={} errorCode={}",
                        message.correlationId(), message.workspaceId(), message.ticketId(), job.getId(), classification.errorCode());
                return WorkerResult.retry(classification.errorCode());
            }
            job.terminalFailure(classification.errorCode(), clock.instant());
            ticket.failed(clock.instant());
            jobs.save(job);
            tickets.save(ticket);
            if (attempt != null) {
                attempt.fail(TriageAttemptStatus.TERMINAL_FAILURE, classification.errorCode(), clock.instant());
                attempts.save(attempt);
            }
            metrics.terminalFailure();
            log.warn("triage.terminal_failure correlationId={} workspaceId={} ticketId={} jobId={} errorCode={}",
                    message.correlationId(), message.workspaceId(), message.ticketId(), job.getId(), classification.errorCode());
            return WorkerResult.terminal(classification.errorCode());
        }
    }
}
