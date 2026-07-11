package com.harishdarko.caselens.triage;

import java.time.Clock;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxEventRepository events;
    private final OutboxPublisher publisher;
    private final Clock clock;
    private final TriageMetrics metrics;

    public OutboxRelay(OutboxEventRepository events, OutboxPublisher publisher, Clock clock) {
        this(events, publisher, clock, TriageMetrics.noop());
    }

    @Autowired
    public OutboxRelay(OutboxEventRepository events, OutboxPublisher publisher, Clock clock, TriageMetrics metrics) {
        this.events = Objects.requireNonNull(events);
        this.publisher = Objects.requireNonNull(publisher);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Transactional
    public int publishBatch(int limit) {
        int published = 0;
        for (OutboxEvent event : events.claimUnpublished(Math.max(1, Math.min(limit, 100)))) {
            try {
                publisher.publish(event);
                event.markPublished(clock.instant());
                metrics.outboxPublished();
                published++;
            } catch (QueuePublishException failure) {
                event.markPublishFailure(failure.errorCode());
                metrics.outboxFailed();
                log.warn("triage.outbox_relay_failed eventId={} errorCode={}", event.getId(), failure.errorCode());
            } catch (RuntimeException failure) {
                event.markPublishFailure("QUEUE_TRANSIENT");
                metrics.outboxFailed();
                log.warn("triage.outbox_relay_failed eventId={} errorCode=QUEUE_TRANSIENT", event.getId());
            }
            events.save(event);
        }
        return published;
    }
}
