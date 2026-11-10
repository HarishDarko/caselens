package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.ticket.Ticket;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

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
    private final DemoFailurePolicy demoFailurePolicy;
    private final PackResolver packs;
    private final TransactionOperations transactions;
    private final TriageFailureClassifier classifier = new TriageFailureClassifier();

    public TriageWorker(TriageJobRepository jobs, com.harishdarko.caselens.ticket.TicketRepository tickets,
            TriageResultRepository results, TriageEngine engine, ObjectMapper mapper, Clock clock) {
        this(jobs, tickets, results, null, engine, mapper, clock, TriageMetrics.noop());
    }

    public TriageWorker(TriageJobRepository jobs, com.harishdarko.caselens.ticket.TicketRepository tickets,
            TriageResultRepository results, TriageAttemptRepository attempts, TriageEngine engine,
            ObjectMapper mapper, Clock clock, TriageMetrics metrics) {
        this(jobs, tickets, results, attempts, engine, mapper, clock, metrics, new DemoFailurePolicy(false));
    }

    public TriageWorker(TriageJobRepository jobs, com.harishdarko.caselens.ticket.TicketRepository tickets,
            TriageResultRepository results, TriageAttemptRepository attempts, TriageEngine engine,
            ObjectMapper mapper, Clock clock, TriageMetrics metrics, DemoFailurePolicy demoFailurePolicy) {
        this(jobs, tickets, results, attempts, engine, mapper, clock, metrics, demoFailurePolicy,
                workspaceId -> PackRules.evCharging());
    }

    public TriageWorker(TriageJobRepository jobs, com.harishdarko.caselens.ticket.TicketRepository tickets,
            TriageResultRepository results, TriageAttemptRepository attempts, TriageEngine engine,
            ObjectMapper mapper, Clock clock, TriageMetrics metrics, DemoFailurePolicy demoFailurePolicy,
            PackResolver packs) {
        this(jobs, tickets, results, attempts, engine, mapper, clock, metrics, demoFailurePolicy, packs,
                TransactionOperations.withoutTransaction());
    }

    public TriageWorker(TriageJobRepository jobs, com.harishdarko.caselens.ticket.TicketRepository tickets,
            TriageResultRepository results, TriageAttemptRepository attempts, TriageEngine engine,
            ObjectMapper mapper, Clock clock, TriageMetrics metrics, DemoFailurePolicy demoFailurePolicy,
            PackResolver packs, TransactionOperations transactions) {
        this.jobs = Objects.requireNonNull(jobs);
        this.tickets = Objects.requireNonNull(tickets);
        this.results = Objects.requireNonNull(results);
        this.attempts = attempts;
        this.engine = Objects.requireNonNull(engine);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
        this.demoFailurePolicy = Objects.requireNonNull(demoFailurePolicy);
        this.packs = Objects.requireNonNull(packs);
        this.transactions = Objects.requireNonNull(transactions);
    }

    public WorkerResult process(QueueMessage message) {
        Timer.Sample latency = metrics.startLatency();
        try {
            return processInternal(message);
        } finally {
            metrics.stopLatency(latency);
        }
    }

    /**
     * Three short steps instead of one long transaction: commit the claim, call the AI provider with no transaction
     * open, then save the outcome. A poll can then see PROCESSING while the provider works, and no database
     * connection is held during the provider call. A worker that dies after the claim leaves the job in PROCESSING
     * until the claim lease ends (TriageJobRepository.CLAIM_LEASE); then the redelivered message takes it again.
     * A delivery that meets a live claim asks for redelivery instead of being dropped (see claimNotTaken), and the
     * final writes only land while this worker still owns the claim (see stillOwned).
     */
    private WorkerResult processInternal(QueueMessage message) {
        Claim claim = transactions.execute(status -> claim(message));
        if (claim.outcome() != null) return claim.outcome();
        TriageJob job = claim.job();
        Ticket ticket = claim.ticket();
        TriageDecision decision;
        try {
            if (demoFailurePolicy.shouldFail(ticket.getScenarioKey(), job.getAttemptCount(), job.getReplayedFromJobId())) {
                throw new SyntheticDemoRetryException();
            }
            decision = engine.triage(new TriageRequest(ticket.getId(), ticket.getSubject(), ticket.getMessage(),
                    ticket.getChannel(), 0, packs.forWorkspace(job.getWorkspaceId())));
        } catch (RuntimeException failure) {
            return transactions.execute(status -> recordFailure(message, claim, failure));
        }
        return transactions.execute(status -> complete(message, claim, decision));
    }

    private Claim claim(QueueMessage message) {
        TriageJob job = jobs.findByEventId(message.eventId()).orElse(null);
        if (job == null) {
            metrics.terminalFailure();
            return Claim.done(WorkerResult.terminal("INVALID_EVENT"));
        }
        if (job.getStatus() == TriageJobStatus.COMPLETED) {
            metrics.duplicateDelivery();
            return Claim.done(WorkerResult.duplicate());
        }
        if (!job.getWorkspaceId().equals(message.workspaceId()) || !job.getTicketId().equals(message.ticketId())) {
            job.terminalFailure("INVALID_EVENT", clock.instant());
            jobs.save(job);
            metrics.terminalFailure();
            log.warn("triage.invalid_event eventId={} jobId={} workspaceId={} ticketId={}",
                    message.eventId(), job.getId(), message.workspaceId(), message.ticketId());
            return Claim.done(WorkerResult.terminal("INVALID_EVENT"));
        }
        if (jobs.claimForProcessing(message.eventId(), clock.instant()) != 1) {
            return Claim.done(claimNotTaken(message));
        }
        job = jobs.findByEventId(message.eventId()).orElse(null);
        if (job == null) {
            metrics.terminalFailure();
            return Claim.done(WorkerResult.terminal("INVALID_EVENT"));
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
            return Claim.done(WorkerResult.terminal("TICKET_NOT_FOUND"));
        }
        ticket.processing(clock.instant());
        tickets.save(ticket);
        return new Claim(job, ticket, attempt, null);
    }

    /**
     * The claim changed no row. A finished job (COMPLETED or TERMINAL_FAILURE) makes this a duplicate delivery. Any
     * other state means another worker holds a live claim (or released it a moment ago). Dropping the message then
     * would leave the job stuck in PROCESSING if that worker dies, so ask SQS to deliver it again: the next delivery
     * sees the finished job or a lapsed lease. maxReceiveCount bounds the retries.
     */
    private WorkerResult claimNotTaken(QueueMessage message) {
        TriageJobStatus status = jobs.findByEventId(message.eventId()).map(TriageJob::getStatus).orElse(null);
        if (status == null) {
            metrics.terminalFailure();
            return WorkerResult.terminal("INVALID_EVENT");
        }
        if (status == TriageJobStatus.COMPLETED || status == TriageJobStatus.TERMINAL_FAILURE) {
            metrics.duplicateDelivery();
            return WorkerResult.duplicate();
        }
        log.info("triage.claim_held correlationId={} workspaceId={} ticketId={} eventId={} status={}",
                message.correlationId(), message.workspaceId(), message.ticketId(), message.eventId(), status);
        return WorkerResult.retry("CLAIM_HELD");
    }

    /**
     * The final writes save the job and ticket loaded in the claim transaction. If this worker's lease lapsed and
     * another worker took the job, those saves would overwrite its newer state, so they only run while the job is
     * still PROCESSING under the attempt this worker claimed. Otherwise the writes are dropped and the message is
     * left for redelivery, which then sees the other worker's outcome or a lapsed lease.
     */
    private boolean stillOwned(QueueMessage message, Claim claim) {
        TriageJob job = claim.job();
        if (jobs.holdClaim(job.getEventId(), job.getAttemptCount(), clock.instant()) == 1) return true;
        log.warn("triage.claim_lost correlationId={} workspaceId={} ticketId={} jobId={} eventId={} attempt={}",
                message.correlationId(), message.workspaceId(), message.ticketId(), job.getId(), message.eventId(),
                job.getAttemptCount());
        return false;
    }

    private WorkerResult complete(QueueMessage message, Claim claim, TriageDecision decision) {
        if (!stillOwned(message, claim)) return WorkerResult.retry("CLAIM_LOST");
        TriageJob job = claim.job();
        Ticket ticket = claim.ticket();
        TriageAttempt attempt = claim.attempt();
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
    }

    private WorkerResult recordFailure(QueueMessage message, Claim claim, RuntimeException failure) {
        if (!stillOwned(message, claim)) return WorkerResult.retry("CLAIM_LOST");
        TriageJob job = claim.job();
        Ticket ticket = claim.ticket();
        TriageAttempt attempt = claim.attempt();
        if (failure instanceof SyntheticDemoRetryException) {
            String errorCode = "SYNTHETIC_PROVIDER_TIMEOUT";
            job.retryableFailure(errorCode, clock.instant());
            ticket.queue(clock.instant());
            jobs.save(job);
            tickets.save(ticket);
            if (attempt != null) {
                attempt.fail(TriageAttemptStatus.RETRYABLE_FAILURE, errorCode, clock.instant());
                attempts.save(attempt);
            }
            metrics.retryableFailure();
            log.warn("triage.synthetic_demo_failure correlationId={} workspaceId={} ticketId={} jobId={} errorCode={}",
                    message.correlationId(), message.workspaceId(), message.ticketId(), job.getId(), errorCode);
            return WorkerResult.ack();
        }
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

    /** The claimed job and ticket, or the outcome when there is nothing to process. */
    private record Claim(TriageJob job, Ticket ticket, TriageAttempt attempt, WorkerResult outcome) {
        static Claim done(WorkerResult outcome) {
            return new Claim(null, null, null, outcome);
        }
    }
}
