package com.harishdarko.caselens.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "tickets")
public class Ticket {
    @Id private UUID id;
    @Column(name = "workspace_id", nullable = false) private UUID workspaceId;
    @Column(name = "display_id", nullable = false, length = 9) private String displayId;
    @Column(nullable = false, length = 160) private String subject;
    @Column(nullable = false, length = 5000) private String message;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private TicketChannel channel;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private TicketStatus status;
    @Column(name = "scenario_key", length = 80) private String scenarioKey;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected Ticket() {}

    private Ticket(UUID id, UUID workspaceId, String subject, String message, TicketChannel channel,
            String scenarioKey, Instant now) {
        this.id = Objects.requireNonNull(id, "Ticket id is required");
        this.workspaceId = Objects.requireNonNull(workspaceId, "Workspace id is required");
        Objects.requireNonNull(subject, "Ticket subject is required");
        Objects.requireNonNull(message, "Ticket message is required");
        this.channel = Objects.requireNonNull(channel, "Ticket channel is required");
        Objects.requireNonNull(now, "Ticket timestamp is required");
        if (subject.length() < 5 || subject.length() > 160) {
            throw new IllegalArgumentException("Ticket subject must contain 5 to 160 characters");
        }
        if (message.length() < 20 || message.length() > 5000) {
            throw new IllegalArgumentException("Ticket message must contain 20 to 5000 characters");
        }
        this.displayId = "CL-" + id.toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        this.subject = subject;
        this.message = message;
        this.status = TicketStatus.NEW;
        this.scenarioKey = scenarioKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Ticket create(UUID id, UUID workspaceId, String subject, String message, TicketChannel channel,
            String scenarioKey, Instant now) {
        return new Ticket(id, workspaceId, subject, message, channel, scenarioKey, now);
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getDisplayId() { return displayId; }
    public String getSubject() { return subject; }
    public String getMessage() { return message; }
    public TicketChannel getChannel() { return channel; }
    public TicketStatus getStatus() { return status; }
    public String getScenarioKey() { return scenarioKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
