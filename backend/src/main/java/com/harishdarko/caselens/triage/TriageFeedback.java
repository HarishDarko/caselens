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
@Table(name = "triage_feedback")
public class TriageFeedback {
    @Id private UUID id;
    @Column(name = "workspace_id", nullable = false) private UUID workspaceId;
    @Column(name = "ticket_id", nullable = false) private UUID ticketId;
    @Column(name = "triage_result_id", nullable = false) private UUID originalResultId;
    @Enumerated(EnumType.STRING) @Column(name = "corrected_category", length = 32) private Category correctedCategory;
    @Enumerated(EnumType.STRING) @Column(name = "corrected_urgency", length = 16) private Urgency correctedUrgency;
    @Enumerated(EnumType.STRING) @Column(name = "corrected_sla_risk", length = 16) private SlaRisk correctedSlaRisk;
    @Column(nullable = false, length = 500) private String note;
    @Column(name = "submitted_by", nullable = false, length = 80) private String submittedBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected TriageFeedback() {}

    private TriageFeedback(UUID id, UUID workspaceId, UUID ticketId, UUID originalResultId,
            Category correctedCategory, Urgency correctedUrgency, SlaRisk correctedSlaRisk,
            String note, String submittedBy, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.workspaceId = Objects.requireNonNull(workspaceId);
        this.ticketId = Objects.requireNonNull(ticketId);
        this.originalResultId = Objects.requireNonNull(originalResultId);
        if (correctedCategory == null && correctedUrgency == null && correctedSlaRisk == null) {
            throw new IllegalArgumentException("At least one correction is required");
        }
        this.correctedCategory = correctedCategory;
        this.correctedUrgency = correctedUrgency;
        this.correctedSlaRisk = correctedSlaRisk;
        this.note = required(note, 500, "Feedback note");
        this.submittedBy = required(submittedBy, 80, "Feedback author");
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static TriageFeedback submit(UUID id, UUID workspaceId, UUID ticketId, UUID originalResultId,
            Category correctedCategory, Urgency correctedUrgency, SlaRisk correctedSlaRisk,
            String note, String submittedBy, Instant createdAt) {
        return new TriageFeedback(id, workspaceId, ticketId, originalResultId, correctedCategory,
                correctedUrgency, correctedSlaRisk, note, submittedBy, createdAt);
    }

    private static String required(String value, int maxLength, String label) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value;
    }

    public boolean differsFrom(TriageResult original) {
        Objects.requireNonNull(original);
        return (correctedCategory != null && correctedCategory != original.getCategory())
                || (correctedUrgency != null && correctedUrgency != original.getUrgency())
                || (correctedSlaRisk != null && correctedSlaRisk != original.getSlaRisk());
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getTicketId() { return ticketId; }
    public UUID getOriginalResultId() { return originalResultId; }
    public Category getCorrectedCategory() { return correctedCategory; }
    public Urgency getCorrectedUrgency() { return correctedUrgency; }
    public SlaRisk getCorrectedSlaRisk() { return correctedSlaRisk; }
    public String getNote() { return note; }
    public String getSubmittedBy() { return submittedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
