package com.harishdarko.caselens.triage;

import java.time.Instant;
import java.util.UUID;

public record ModelInvocationRecord(UUID ticketId, String provider, String modelVersion, String promptVersion,
        Instant startedAt, Instant endedAt, long latencyMs, String status, String sanitizedErrorCode,
        Long inputTokens, Long outputTokens) {}
