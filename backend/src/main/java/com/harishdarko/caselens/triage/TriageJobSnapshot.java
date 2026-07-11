package com.harishdarko.caselens.triage;

import java.time.Instant;
import java.util.UUID;

public record TriageJobSnapshot(UUID jobId, UUID eventId, UUID workspaceId, UUID ticketId, TriageJobStatus status,
        int attemptCount, Instant startedAt, Instant completedAt, String lastErrorCode) {}
