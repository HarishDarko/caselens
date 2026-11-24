package com.harishdarko.caselens.triage;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "triage_feedback")
public class TriageFeedback implements Persistable<UUID> {
    @Id private UUID id;
    @Column(name = "workspace_id", nullable = false) private UUID workspaceId;
    @Column(name = "ticket_id", nullable = false) private UUID ticketId;
    @Column(name = "triage_result_id", nullable = false) private UUID originalResultId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private FeedbackDecision decision;
    @Convert(converter = CategoryConverter.class) @Column(name = "corrected_category", length = 32) private Category correctedCategory;
    @Enumerated(EnumType.STRING) @Column(name = "corrected_urgency", length = 16) private Urgency correctedUrgency;
    @Enumerated(EnumType.STRING) @Column(name = "corrected_sla_risk", length = 16) private SlaRisk correctedSlaRisk;
    @Column(length = 500) private String note;
    @Column(name = "submitted_by", nullable = false, length = 80) private String submittedBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected TriageFeedback() {}

    private TriageFeedback(UUID id, UUID workspaceId, UUID ticketId, UUID originalResultId, FeedbackDecision decision,
            Category correctedCategory, Urgency correctedUrgency, SlaRisk correctedSlaRisk,
            String note, String submittedBy, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.workspaceId = Objects.requireNonNull(workspaceId);
        this.ticketId = Objects.requireNonNull(ticketId);
        this.originalResultId = Objects.requireNonNull(originalResultId);
        this.decision = Objects.requireNonNull(decision);
        boolean changes = correctedCategory != null || correctedUrgency != null || correctedSlaRisk != null;
        if (decision == FeedbackDecision.CORRECTED && !changes) {
            throw new IllegalArgumentException("At least one correction is required");
        }
        if (decision == FeedbackDecision.ACCEPTED && changes) {
            throw new IllegalArgumentException("An accepted result keeps every field");
        }
        this.correctedCategory = correctedCategory;
        this.correctedUrgency = correctedUrgency;
        this.correctedSlaRisk = correctedSlaRisk;
        this.note = optionalNote(note);
        this.submittedBy = required(submittedBy, 80, "Feedback author");
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    /** A correction, as before decisions existed. */
    public static TriageFeedback submit(UUID id, UUID workspaceId, UUID ticketId, UUID originalResultId,
            Category correctedCategory, Urgency correctedUrgency, SlaRisk correctedSlaRisk,
            String note, String submittedBy, Instant createdAt) {
        return submit(id, workspaceId, ticketId, originalResultId, FeedbackDecision.CORRECTED, correctedCategory,
                correctedUrgency, correctedSlaRisk, note, submittedBy, createdAt);
    }

    public static TriageFeedback submit(UUID id, UUID workspaceId, UUID ticketId, UUID originalResultId,
            FeedbackDecision decision, Category correctedCategory, Urgency correctedUrgency, SlaRisk correctedSlaRisk,
            String note, String submittedBy, Instant createdAt) {
        return new TriageFeedback(id, workspaceId, ticketId, originalResultId, decision, correctedCategory,
                correctedUrgency, correctedSlaRisk, note, submittedBy, createdAt);
    }

    /** A blank note is no note: the review bar sends none when the reason is left empty. */
    static String optionalNote(String note) {
        if (note == null || note.isBlank()) return null;
        if (note.length() > 500) throw new IllegalArgumentException("Feedback note is invalid");
        return note;
    }

    /**
     * True when a resent submit carries the same body as this entry. A resend without a result id names the ticket's
     * latest result, which this entry recorded, so only an explicit result id is compared.
     */
    boolean sameSubmission(UUID ticketId, UUID requestedResultId, FeedbackDecision decision, Category category,
            Urgency urgency, SlaRisk slaRisk, String note) {
        return this.ticketId.equals(ticketId)
                && (requestedResultId == null || originalResultId.equals(requestedResultId))
                && this.decision == decision
                && Objects.equals(correctedCategory, category)
                && correctedUrgency == urgency
                && correctedSlaRisk == slaRisk
                && Objects.equals(this.note, optionalNote(note));
    }

    private static String required(String value, int maxLength, String label) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value;
    }

    public boolean differsFrom(TriageResult original) {
        Objects.requireNonNull(original);
        return differsFrom(original.getCategory(), original.getUrgency(), original.getSlaRisk());
    }

    public boolean differsFrom(ResultFacts original) {
        Objects.requireNonNull(original);
        return differsFrom(original.category(), original.urgency(), original.slaRisk());
    }

    private boolean differsFrom(Category category, Urgency urgency, SlaRisk slaRisk) {
        return (correctedCategory != null && !correctedCategory.equals(category))
                || (correctedUrgency != null && correctedUrgency != urgency)
                || (correctedSlaRisk != null && correctedSlaRisk != slaRisk);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getTicketId() { return ticketId; }
    public UUID getOriginalResultId() { return originalResultId; }
    public FeedbackDecision getDecision() { return decision; }
    public Category getCorrectedCategory() { return correctedCategory; }
    public Urgency getCorrectedUrgency() { return correctedUrgency; }
    public SlaRisk getCorrectedSlaRisk() { return correctedSlaRisk; }
    public String getNote() { return note; }
    public String getSubmittedBy() { return submittedBy; }
    public Instant getCreatedAt() { return createdAt; }

    /**
     * New until it is inserted or loaded. Spring Data then saves a new instance with persist (one INSERT) instead of
     * merge (a SELECT, then an INSERT or an UPDATE). With client-chosen ids (a scenario or feedback requestId) a
     * merge could update another workspace's row that won a race on the same id; persist fails on the primary key.
     */
    @Transient private boolean stored;

    @PostLoad
    @PostPersist
    void markStored() { stored = true; }

    @Override
    public boolean isNew() { return !stored; }
}
