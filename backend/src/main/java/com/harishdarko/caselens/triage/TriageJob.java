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
@Table(name = "triage_job")
public class TriageJob {
    @Id private UUID id;
    @Column(name = "event_id", nullable = false, unique = true) private UUID eventId;
    @Column(name = "workspace_id", nullable = false) private UUID workspaceId;
    @Column(name = "ticket_id", nullable = false) private UUID ticketId;
    @Column(name = "content_version", nullable = false) private int contentVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private TriageJobStatus status;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "last_error_code", length = 80) private String lastErrorCode;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "replayed_from_job_id") private UUID replayedFromJobId;

    protected TriageJob() {}

    private TriageJob(UUID id, UUID eventId, UUID workspaceId, UUID ticketId, int contentVersion, Instant now) {
        this.id = Objects.requireNonNull(id);
        this.eventId = Objects.requireNonNull(eventId);
        this.workspaceId = Objects.requireNonNull(workspaceId);
        this.ticketId = Objects.requireNonNull(ticketId);
        this.contentVersion = contentVersion;
        this.status = TriageJobStatus.QUEUED;
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    public static TriageJob queued(UUID id, UUID eventId, UUID workspaceId, UUID ticketId, int contentVersion, Instant now) {
        return new TriageJob(id, eventId, workspaceId, ticketId, contentVersion, now);
    }

    public boolean claim(Instant now) {
        if (status != TriageJobStatus.QUEUED && status != TriageJobStatus.RETRYABLE_FAILURE) return false;
        status = TriageJobStatus.PROCESSING;
        attemptCount++;
        startedAt = Objects.requireNonNull(now);
        updatedAt = now;
        return true;
    }

    public void complete(Instant now) {
        status = TriageJobStatus.COMPLETED;
        completedAt = Objects.requireNonNull(now);
        updatedAt = now;
        lastErrorCode = null;
    }

    public void retryableFailure(String errorCode, Instant now) {
        status = TriageJobStatus.RETRYABLE_FAILURE;
        lastErrorCode = errorCode;
        updatedAt = Objects.requireNonNull(now);
    }

    public void terminalFailure(String errorCode, Instant now) {
        status = TriageJobStatus.TERMINAL_FAILURE;
        lastErrorCode = errorCode;
        updatedAt = Objects.requireNonNull(now);
    }

    public TriageJobSnapshot snapshot() {
        return new TriageJobSnapshot(id, eventId, workspaceId, ticketId, status, attemptCount, startedAt, completedAt, lastErrorCode);
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getTicketId() { return ticketId; }
    public int getContentVersion() { return contentVersion; }
    public TriageJobStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getLastErrorCode() { return lastErrorCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getReplayedFromJobId() { return replayedFromJobId; }
    public void setReplayedFromJobId(UUID replayedFromJobId) { this.replayedFromJobId = replayedFromJobId; }
}
