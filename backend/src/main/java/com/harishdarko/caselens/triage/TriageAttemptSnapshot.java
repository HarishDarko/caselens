package com.harishdarko.caselens.triage;

import java.time.Instant;
import java.util.UUID;

public record TriageAttemptSnapshot(UUID id, UUID jobId, int attemptNumber, TriageAttemptStatus status,
        Instant startedAt, Instant completedAt, String errorCode) {}
