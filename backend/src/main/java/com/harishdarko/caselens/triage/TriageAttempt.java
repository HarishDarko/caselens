package com.harishdarko.caselens.triage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "triage_attempt")
public class TriageAttempt {
    @Id private UUID id;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "attempt_number", nullable = false) private int attemptNumber;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private TriageAttemptStatus status;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "error_code", length = 80) private String errorCode;

    protected TriageAttempt() {}

    private TriageAttempt(UUID jobId, int attemptNumber, Instant startedAt) {
        this.id = UUID.randomUUID();
        this.jobId = Objects.requireNonNull(jobId);
        if (attemptNumber < 1) throw new IllegalArgumentException("Attempt number must be positive");
        this.attemptNumber = attemptNumber;
        this.status = TriageAttemptStatus.PROCESSING;
        this.startedAt = Objects.requireNonNull(startedAt);
    }

    public static TriageAttempt processing(UUID jobId, int attemptNumber, Instant startedAt) {
        return new TriageAttempt(jobId, attemptNumber, startedAt);
    }

    public void complete(Instant now) {
        status = TriageAttemptStatus.COMPLETED;
        completedAt = Objects.requireNonNull(now);
        errorCode = null;
    }

    public void fail(TriageAttemptStatus failureStatus, String code, Instant now) {
        if (failureStatus != TriageAttemptStatus.RETRYABLE_FAILURE
                && failureStatus != TriageAttemptStatus.TERMINAL_FAILURE) {
            throw new IllegalArgumentException("Attempt failure status is required");
        }
        status = failureStatus;
        errorCode = Objects.requireNonNull(code);
        completedAt = Objects.requireNonNull(now);
    }

    public TriageAttemptSnapshot snapshot() {
        return new TriageAttemptSnapshot(id, jobId, attemptNumber, status, startedAt, completedAt, errorCode);
    }

    public UUID getId() { return id; }
    public UUID getJobId() { return jobId; }
    public int getAttemptNumber() { return attemptNumber; }
    public TriageAttemptStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getErrorCode() { return errorCode; }
}
