package com.harishdarko.caselens.triage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class TriageMetrics {
    private final MeterRegistry registry;
    private final Counter requested;
    private final Counter completed;
    private final Counter fallback;
    private final Counter retryableFailure;
    private final Counter terminalFailure;
    private final Counter duplicateDelivery;
    private final Counter outboxPublished;
    private final Counter outboxFailed;
    private final Timer latency;

    public TriageMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.requested = counter("requested");
        this.completed = counter("completed");
        this.fallback = counter("fallback");
        this.retryableFailure = counter("retryable.failure");
        this.terminalFailure = counter("terminal.failure");
        this.duplicateDelivery = counter("duplicate.delivery");
        this.outboxPublished = counter("outbox.published");
        this.outboxFailed = counter("outbox.failed");
        this.latency = Timer.builder("caselens.triage.latency").description("Triage processing latency")
                .publishPercentiles(0.5, 0.95).register(registry);
    }

    public static TriageMetrics noop() { return new TriageMetrics(new SimpleMeterRegistry()); }

    private Counter counter(String name) {
        return Counter.builder("caselens.triage." + name).register(registry);
    }

    public void requested() { requested.increment(); }
    public void completed() { completed.increment(); }
    public void fallback() { fallback.increment(); }
    public void retryableFailure() { retryableFailure.increment(); }
    public void terminalFailure() { terminalFailure.increment(); }
    public void duplicateDelivery() { duplicateDelivery.increment(); }
    public void outboxPublished() { outboxPublished.increment(); }
    public void outboxFailed() { outboxFailed.increment(); }
    public Timer.Sample startLatency() { return Timer.start(registry); }
    public void stopLatency(Timer.Sample sample) { sample.stop(latency); }
}
