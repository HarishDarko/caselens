package com.harishdarko.caselens.triage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OperationsOverview(
        Instant generatedAt,
        QueueCounts queue,
        long fallbackCount,
        long providerFailureCount,
        List<RecentOperation> recent,
        List<ProviderHealth> providers) {

    public OperationsOverview {
        generatedAt = Objects.requireNonNull(generatedAt);
        queue = Objects.requireNonNull(queue);
        if (fallbackCount < 0 || providerFailureCount < 0) throw new IllegalArgumentException("Operation counts cannot be negative");
        recent = List.copyOf(Objects.requireNonNull(recent));
        providers = List.copyOf(Objects.requireNonNull(providers));
    }

    public record QueueCounts(long queued, long processing, long completed, long retryableFailures, long terminalFailures) {
        public QueueCounts {
            if (queued < 0 || processing < 0 || completed < 0 || retryableFailures < 0 || terminalFailures < 0) {
                throw new IllegalArgumentException("Queue counts cannot be negative");
            }
        }
    }

    public record RecentOperation(
            UUID jobId,
            UUID ticketId,
            TriageJobStatus status,
            int attemptCount,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            String lastErrorCode,
            String provider,
            String modelVersion,
            String decisionSource,
            long latencyMs) {
        public RecentOperation {
            jobId = Objects.requireNonNull(jobId);
            ticketId = Objects.requireNonNull(ticketId);
            status = Objects.requireNonNull(status);
            createdAt = Objects.requireNonNull(createdAt);
            updatedAt = Objects.requireNonNull(updatedAt);
            if (attemptCount < 0 || latencyMs < 0) throw new IllegalArgumentException("Operation values cannot be negative");
        }
    }

    public record ProviderHealth(
            String provider,
            long requests,
            long successes,
            long failures,
            long inputTokens,
            long outputTokens,
            long averageLatencyMs) {
        public ProviderHealth {
            provider = Objects.requireNonNull(provider);
            if (requests < 0 || successes < 0 || failures < 0 || inputTokens < 0 || outputTokens < 0 || averageLatencyMs < 0) {
                throw new IllegalArgumentException("Provider values cannot be negative");
            }
        }
    }
}
