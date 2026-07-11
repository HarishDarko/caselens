package com.harishdarko.caselens.triage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
public class OutboxEvent {
    @Id private UUID id;
    @Column(name = "workspace_id", nullable = false) private UUID workspaceId;
    @Column(name = "ticket_id", nullable = false) private UUID ticketId;
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, length = 32) private OutboxEventType eventType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private String payload;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "publish_attempts", nullable = false) private int publishAttempts;
    @Column(name = "last_error_code", length = 80) private String lastErrorCode;

    protected OutboxEvent() {}

    private OutboxEvent(UUID id, UUID workspaceId, UUID ticketId, String correlationId, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.workspaceId = Objects.requireNonNull(workspaceId);
        this.ticketId = Objects.requireNonNull(ticketId);
        this.eventType = OutboxEventType.TRIAGE_REQUESTED;
        this.payload = "{\"schemaVersion\":1,\"eventId\":\"" + id + "\",\"workspaceId\":\""
                + workspaceId + "\",\"ticketId\":\"" + ticketId + "\",\"correlationId\":\""
                + CorrelationIds.normalize(correlationId) + "\"}";
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static OutboxEvent create(UUID workspaceId, UUID ticketId, String correlationId, Instant now) {
        return new OutboxEvent(UUID.randomUUID(), workspaceId, ticketId, correlationId, now);
    }

    public void markPublished(Instant now) { this.publishedAt = Objects.requireNonNull(now); }
    public void markPublishFailure(String errorCode) { this.publishAttempts++; this.lastErrorCode = errorCode; }
    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getTicketId() { return ticketId; }
    public OutboxEventType getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getPublishAttempts() { return publishAttempts; }
    public String getLastErrorCode() { return lastErrorCode; }
}
