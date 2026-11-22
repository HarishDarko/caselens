package com.harishdarko.caselens.triage;

import java.time.Instant;
import java.util.UUID;

/** One provider call. eventId names the triage job's outbox event; it is null for calls made outside a queued job. */
public record ModelInvocationRecord(UUID ticketId, UUID eventId, String provider, String modelVersion, String promptVersion,
        Instant startedAt, Instant endedAt, long latencyMs, String status, String sanitizedErrorCode,
        Long inputTokens, Long outputTokens) {

    public ModelInvocationRecord(UUID ticketId, String provider, String modelVersion, String promptVersion,
            Instant startedAt, Instant endedAt, long latencyMs, String status, String sanitizedErrorCode,
            Long inputTokens, Long outputTokens) {
        this(ticketId, null, provider, modelVersion, promptVersion, startedAt, endedAt, latencyMs, status,
                sanitizedErrorCode, inputTokens, outputTokens);
    }
}
