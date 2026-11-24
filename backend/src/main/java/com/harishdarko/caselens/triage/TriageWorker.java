package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.ticket.Ticket;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

public class TriageWorker {
    private static final Logger log = LoggerFactory.getLogger(TriageWorker.class);
    static final String CLAIM_LEASE_EXPIRED = "CLAIM_LEASE_EXPIRED";
    /** SQS maxReceiveCount on the triage queue (infra/terraform/main.tf, checked by WorkerTimingContractTest). */
    static final int MAX_DELIVERIES = 5;
    /**
     * The last delivery a rate-limited provider call is retried on: the first is retried once by SQS (90 s later),
     * the second falls back to the rules. A visitor then waits at most one redelivery plus one worker run, inside the
     * page's 150 s wait, and a rate limit never sends a job to the DLQ.
     */
    static final int RATE_LIMIT_DELIVERIES = 2;
    /**
     * Time kept back from the Lambda's remaining time for what follows the provider call: the invocation record in its
     * own transaction (which may need a fresh connection, up to Hikari's 10 s connect timeout), the rules fallback and
     * the result commit. The provider call gets the rest (TriageRequest.timeBudget).
     */
    static final Duration POST_CALL_RESERVE = Duration.ofSeconds(14);
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

    /** A delivery whose receive count is not known is treated as the final one: every failure falls back. */
    public WorkerResult process(QueueMessage message) {
        return process(message, 0);
    }

    public WorkerResult process(QueueMessage message, int receiveCount) {
        return process(message, receiveCount, null);
    }

    /**
     * {@code receiveCount} is SQS's ApproximateReceiveCount for this delivery (1 for the first), or 0 when unknown.
     * Before RATE_LIMIT_DELIVERIES a rate-limited provider call ends the attempt as a retryable failure and SQS
     * delivers the message again after its visibility timeout. {@code remainingMillis} is the Lambda's remaining time
     * (null outside Lambda); the provider call gets it less POST_CALL_RESERVE, read when the provider needs it. A
     * delivery with too little time left for a useful provider call makes none, spends no AI budget and ends as a
     * retryable failure, or falls back to the rules on the final delivery.
     */
    public WorkerResult process(QueueMessage message, int receiveCount, LongSupplier remainingMillis) {
        Timer.Sample latency = metrics.startLatency();
        Instant received = clock.instant();
        try {
            return processInternal(message, receiveCount <= 0 || receiveCount >= RATE_LIMIT_DELIVERIES, remainingMillis, received);
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
     * final writes only land while this worker still owns the claim (see stillOwned). Each step takes the ticket row
     * lock before it touches the job (see lockTicket).
     */
    private WorkerResult processInternal(QueueMessage message, boolean finalDelivery, LongSupplier remainingMillis,
            Instant received) {
        List<Runnable> afterClaim = new ArrayList<>();
        Claim claim = transactions.execute(status -> {
            afterClaim.clear();
            return claim(message, afterClaim);
        });
        // After the commit, like complete(): counts and lines only describe what was saved.
        afterClaim.forEach(Runnable::run);
        if (claim.outcome() != null) return claim.outcome();
        TriageJob job = claim.job();
        Ticket ticket = claim.ticket();
        TriageDecision decision;
        try {
            if (demoFailurePolicy.shouldFail(ticket.getScenarioKey(), job.getAttemptCount(), job.getReplayedFromJobId())) {
                throw new SyntheticDemoRetryException();
            }
            TriageRequest request = new TriageRequest(ticket.getId(), ticket.getSubject(), ticket.getMessage(),
                    ticket.getChannel(), 0, packs.forWorkspace(job.getWorkspaceId()));
            // Both are read when a provider needs them, not here: database work between now and the call (the AI
            // budget check) must count against the time left, and SQS's visibility clock runs from the receipt.
            Supplier<Duration> timeBudget = remainingMillis == null ? null
                    : () -> Duration.ofMillis(remainingMillis.getAsLong()).minus(POST_CALL_RESERVE);
            Supplier<Duration> sinceReceived = () -> Duration.between(received, clock.instant());
            decision = engine.triage(request.withCallContext(timeBudget, sinceReceived), job.getEventId(), finalDelivery);
        } catch (RuntimeException failure) {
            List<Runnable> afterFailure = new ArrayList<>();
            WorkerResult failed = transactions.execute(status -> {
                afterFailure.clear();
                return recordFailure(message, claim, failure, afterFailure);
            });
            afterFailure.forEach(Runnable::run);
            return failed;
        }
        WorkerResult outcome = transactions.execute(status -> complete(message, claim, decision));
        // After the commit, so the counts and lines only ever describe a result that was saved.
        if (outcome.status() == WorkerResultStatus.ACK) {
            metrics.completed();
            if (decision.decisionSource() == DecisionSource.RULES_FALLBACK) metrics.fallback();
            log.info("triage.completed correlationId={} workspaceId={} ticketId={} jobId={} eventId={} source={}",
                    message.correlationId(), message.workspaceId(), message.ticketId(), job.getId(),
                    message.eventId(), decision.decisionSource());
            if (decision.decisionSource() == DecisionSource.RULES_FALLBACK) logFallback(message, job, decision.fallback());
        }
        return outcome;
    }

    private Claim claim(QueueMessage message, List<Runnable> after) {
        lockTicket(message.ticketId(), message.workspaceId());
        TriageJob existing = jobs.findByEventId(message.eventId()).orElse(null);
        if (existing == null) {
            after.add(metrics::terminalFailure);
            return Claim.done(WorkerResult.terminal("INVALID_EVENT"));
        }
        if (existing.getStatus() == TriageJobStatus.COMPLETED) {
            after.add(metrics::duplicateDelivery);
            return Claim.done(WorkerResult.duplicate());
        }
        if (existing.getStatus() == TriageJobStatus.RETRYABLE_FAILURE && jobs.existsByReplayedFromJobId(existing.getId())) {
            // A reviewer replayed this job after SQS gave up on it (a DLQ redrive can still bring the old message
            // back). The replacement owns the ticket now, so this delivery must not call the provider again.
            UUID supersededId = existing.getId();
            after.add(metrics::duplicateDelivery);
            after.add(() -> log.info("triage.superseded_delivery correlationId={} workspaceId={} ticketId={} jobId={} eventId={}",
                    message.correlationId(), message.workspaceId(), message.ticketId(), supersededId, message.eventId()));
            return Claim.done(WorkerResult.duplicate());
        }
        if (!existing.getWorkspaceId().equals(message.workspaceId()) || !existing.getTicketId().equals(message.ticketId())) {
            UUID invalidId = existing.getId();
            existing.terminalFailure("INVALID_EVENT", clock.instant());
            jobs.save(existing);
            after.add(metrics::terminalFailure);
            after.add(() -> log.warn("triage.invalid_event eventId={} jobId={} workspaceId={} ticketId={}",
                    message.eventId(), invalidId, message.workspaceId(), message.ticketId()));
            return Claim.done(WorkerResult.terminal("INVALID_EVENT"));
        }
        if (jobs.claimForProcessing(message.eventId(), clock.instant()) != 1) {
            return Claim.done(claimNotTaken(message, after));
        }
        TriageJob job = jobs.findByEventId(message.eventId()).orElse(null);
        if (job == null) {
            after.add(metrics::terminalFailure);
            return Claim.done(WorkerResult.terminal("INVALID_EVENT"));
        }
        closeAbandonedAttempts(message, job, after);
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
            after.add(metrics::terminalFailure);
            return Claim.done(WorkerResult.terminal("TICKET_NOT_FOUND"));
        }
        ticket.processing(clock.instant());
        tickets.save(ticket);
        return new Claim(job, ticket, attempt, null);
    }

    /**
     * Takes the ticket row lock for the rest of the step's transaction, before any write to the job. Request, replay
     * and reset lock the ticket first too (reset deletes it, which cascades to its jobs), so all of them take the
     * ticket, then the job. The worker used to update the job first and the ticket last, which deadlocked against a
     * reviewer replay of the same ticket (CL3-01). A missing ticket takes no lock; the steps below handle that case.
     */
    private void lockTicket(java.util.UUID ticketId, java.util.UUID workspaceId) {
        tickets.findByIdAndWorkspaceIdForUpdate(ticketId, workspaceId);
    }

    /**
     * A claim that took over a lapsed lease leaves the earlier worker's attempt in PROCESSING, because that worker
     * timed out or died before it could record an outcome (and stillOwned stops it from writing one later). Close
     * those attempts as retryable failures so the timeline shows what happened instead of two live attempts.
     */
    private void closeAbandonedAttempts(QueueMessage message, TriageJob job, List<Runnable> after) {
        if (attempts == null) return;
        Instant now = clock.instant();
        for (TriageAttempt earlier : attempts.findByJobIdOrderByAttemptNumberAsc(job.getId())) {
            if (earlier.getStatus() != TriageAttemptStatus.PROCESSING || earlier.getAttemptNumber() >= job.getAttemptCount()) {
                continue;
            }
            earlier.fail(TriageAttemptStatus.RETRYABLE_FAILURE, CLAIM_LEASE_EXPIRED, now);
            attempts.save(earlier);
            UUID jobId = job.getId();
            int attemptNumber = earlier.getAttemptNumber();
            after.add(() -> log.warn("triage.claim_lease_expired correlationId={} workspaceId={} ticketId={} jobId={} eventId={} attempt={}",
                    message.correlationId(), message.workspaceId(), message.ticketId(), jobId, message.eventId(), attemptNumber));
        }
    }

    /**
     * The claim changed no row. A finished job (COMPLETED or TERMINAL_FAILURE) makes this a duplicate delivery. Any
     * other state means another worker holds a live claim (or released it a moment ago). Dropping the message then
     * would leave the job stuck in PROCESSING if that worker dies, so ask SQS to deliver it again: the next delivery
     * sees the finished job or a lapsed lease. maxReceiveCount bounds the retries.
     */
    private WorkerResult claimNotTaken(QueueMessage message, List<Runnable> after) {
        TriageJobStatus status = jobs.findByEventId(message.eventId()).map(TriageJob::getStatus).orElse(null);
        if (status == null) {
            after.add(metrics::terminalFailure);
            return WorkerResult.terminal("INVALID_EVENT");
        }
        if (status == TriageJobStatus.COMPLETED || status == TriageJobStatus.TERMINAL_FAILURE) {
            after.add(metrics::duplicateDelivery);
            return WorkerResult.duplicate();
        }
        after.add(() -> log.info("triage.claim_held correlationId={} workspaceId={} ticketId={} eventId={} status={}",
                message.correlationId(), message.workspaceId(), message.ticketId(), message.eventId(), status));
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
        lockTicket(claim.job().getTicketId(), claim.job().getWorkspaceId());
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
        return WorkerResult.ack();
    }

    /**
     * One WARN line per saved rules-fallback result, written after its transaction commits, so the reasons can be
     * counted in CloudWatch Logs Insights. Every value is an id or a fixed code (see FallbackCause): never ticket text,
     * evidence quotes or model output.
     */
    private void logFallback(QueueMessage message, TriageJob job, FallbackCause cause) {
        FallbackCause known = cause != null ? cause : FallbackCause.of(null, null, null, null);
        log.warn("triage.fallback correlationId={} workspaceId={} ticketId={} jobId={} eventId={} pack={} provider={}"
                        + " errorCode={} check={} reason={}",
                message.correlationId(), message.workspaceId(), message.ticketId(), job.getId(), message.eventId(),
                known.pack(), known.provider(), known.errorCode(),
                known.failedCheck() == null ? "none" : known.failedCheck(), known.reason());
    }

    /** The metrics and log lines go in {@code after}, which the caller runs once the transaction has committed. */
    private WorkerResult recordFailure(QueueMessage message, Claim claim, RuntimeException failure, List<Runnable> after) {
        lockTicket(claim.job().getTicketId(), claim.job().getWorkspaceId());
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
            after.add(metrics::retryableFailure);
            after.add(() -> log.warn("triage.synthetic_demo_failure correlationId={} workspaceId={} ticketId={} jobId={} errorCode={}",
                    message.correlationId(), message.workspaceId(), message.ticketId(), job.getId(), errorCode));
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
            after.add(metrics::retryableFailure);
            after.add(() -> log.warn("triage.retryable_failure correlationId={} workspaceId={} ticketId={} jobId={} errorCode={}",
                    message.correlationId(), message.workspaceId(), message.ticketId(), job.getId(), classification.errorCode()));
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
        after.add(metrics::terminalFailure);
        after.add(() -> log.warn("triage.terminal_failure correlationId={} workspaceId={} ticketId={} jobId={} errorCode={}",
                message.correlationId(), message.workspaceId(), message.ticketId(), job.getId(), classification.errorCode()));
        return WorkerResult.terminal(classification.errorCode());
    }

    /** The claimed job and ticket, or the outcome when there is nothing to process. */
    private record Claim(TriageJob job, Ticket ticket, TriageAttempt attempt, WorkerResult outcome) {
        static Claim done(WorkerResult outcome) {
            return new Claim(null, null, null, outcome);
        }
    }
}
