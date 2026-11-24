package com.harishdarko.caselens.triage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** latencyMs is null when the result has no job to measure it from. */
public record EvaluationEvent(
        UUID resultId,
        Instant createdAt,
        Long latencyMs,
        String provider,
        String modelVersion,
        String decisionSource,
        boolean evaluated,
        boolean corrected) {
    public EvaluationEvent {
        resultId = Objects.requireNonNull(resultId);
        createdAt = Objects.requireNonNull(createdAt);
        if (latencyMs != null && latencyMs < 0) throw new IllegalArgumentException("Evaluation latency cannot be negative");
        decisionSource = Objects.requireNonNull(decisionSource);
    }
}
