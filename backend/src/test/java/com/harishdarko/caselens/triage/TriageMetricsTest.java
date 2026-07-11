package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class TriageMetricsTest {
    @Test
    void exposesSafeCountersAndLatencyTimerForOperations() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TriageMetrics metrics = new TriageMetrics(registry);

        metrics.requested();
        metrics.completed();
        metrics.fallback();
        metrics.retryableFailure();
        metrics.terminalFailure();
        metrics.duplicateDelivery();
        metrics.outboxPublished();
        metrics.outboxFailed();
        var sample = metrics.startLatency();
        metrics.stopLatency(sample);

        assertThat(registry.counter("caselens.triage.requested").count()).isOne();
        assertThat(registry.counter("caselens.triage.completed").count()).isOne();
        assertThat(registry.counter("caselens.triage.fallback").count()).isOne();
        assertThat(registry.timer("caselens.triage.latency").count()).isOne();
    }
}
