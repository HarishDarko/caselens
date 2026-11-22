package com.harishdarko.caselens.triage;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox events that the request path could not publish right after its commit. Each event gets its own
 * short transaction: lock that one row (SKIP LOCKED, so two relays never publish the same row at once), send it, and
 * record the outcome with a conditional update that only touches a row that is still unpublished. A slow SQS call
 * therefore holds one row lock for at most the SQS client's call timeout, never the whole batch. Events are tried
 * fewest attempts first, so a row that keeps failing cannot hold back newer events, and no event is ever dropped:
 * an unpublished event stays a candidate until a publish succeeds. The batch stops early when the caller's time
 * budget (the Lambda's remaining time) runs low, so a slow SQS cannot run the relay into its timeout.
 */
@Service
public class OutboxRelay {
    /** Stop starting new publishes below this much remaining time: one SQS call can take up to 6 s (CL-18). */
    static final long MIN_REMAINING_MILLIS = 8_000;
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxEventRepository events;
    private final OutboxPublisher publisher;
    private final Clock clock;
    private final TriageMetrics metrics;
    private final TransactionOperations transactions;

    public OutboxRelay(OutboxEventRepository events, OutboxPublisher publisher, Clock clock) {
        this(events, publisher, clock, TriageMetrics.noop(), TransactionOperations.withoutTransaction());
    }

    public OutboxRelay(OutboxEventRepository events, OutboxPublisher publisher, Clock clock, TriageMetrics metrics) {
        this(events, publisher, clock, metrics, TransactionOperations.withoutTransaction());
    }

    @Autowired
    public OutboxRelay(OutboxEventRepository events, OutboxPublisher publisher, Clock clock, TriageMetrics metrics,
            PlatformTransactionManager transactionManager) {
        this(events, publisher, clock, metrics, new TransactionTemplate(transactionManager));
    }

    OutboxRelay(OutboxEventRepository events, OutboxPublisher publisher, Clock clock, TriageMetrics metrics,
            TransactionOperations transactions) {
        this.events = Objects.requireNonNull(events);
        this.publisher = Objects.requireNonNull(publisher);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
        this.transactions = Objects.requireNonNull(transactions);
    }

    public int publishBatch(int limit) {
        return publishBatch(limit, () -> Long.MAX_VALUE);
    }

    /** Publishes up to limit events, starting no new publish once remainingMillis drops below MIN_REMAINING_MILLIS. */
    public int publishBatch(int limit, LongSupplier remainingMillis) {
        int published = 0;
        List<UUID> candidates = events.findPublishCandidates(Math.max(1, Math.min(limit, 100))).stream()
                .map(OutboxEvent::getId).toList();
        for (UUID eventId : candidates) {
            if (remainingMillis.getAsLong() < MIN_REMAINING_MILLIS) {
                log.warn("triage.outbox_relay_time_low published={} left={}", published,
                        candidates.size() - candidates.indexOf(eventId));
                break;
            }
            if (Boolean.TRUE.equals(transactions.execute(status -> publishOne(eventId)))) published++;
        }
        return published;
    }

    private boolean publishOne(UUID eventId) {
        OutboxEvent event = events.lockUnpublished(eventId).orElse(null);
        if (event == null) return false; // published since the candidate query, or another relay holds the row
        try {
            publisher.publish(event);
        } catch (QueuePublishException failure) {
            recordFailure(event, failure.errorCode());
            return false;
        } catch (RuntimeException failure) {
            recordFailure(event, "QUEUE_TRANSIENT");
            return false;
        }
        events.markPublished(event.getId(), clock.instant());
        metrics.outboxPublished();
        return true;
    }

    private void recordFailure(OutboxEvent event, String errorCode) {
        events.recordPublishFailure(event.getId(), errorCode);
        metrics.outboxFailed();
        log.warn("triage.outbox_relay_failed eventId={} attempts={} errorCode={}", event.getId(),
                event.getPublishAttempts() + 1, errorCode);
    }
}
