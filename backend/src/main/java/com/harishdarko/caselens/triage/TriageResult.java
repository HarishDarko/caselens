package com.harishdarko.caselens.triage;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Entity
@Table(name = "triage_result")
public class TriageResult {
    @Id private UUID id;
    /** The queued event this result answered; null only for a recorded result, which had no event. */
    @Column(name = "event_id", unique = true) private UUID eventId;
    @Column(name = "workspace_id", nullable = false) private UUID workspaceId;
    @Column(name = "ticket_id", nullable = false) private UUID ticketId;
    @Convert(converter = CategoryConverter.class) @Column(nullable = false, length = 32) private Category category;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Urgency urgency;
    @Enumerated(EnumType.STRING) @Column(name = "sla_risk", nullable = false, length = 16) private SlaRisk slaRisk;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Sentiment sentiment;
    @Column(nullable = false, length = 300) private String summary;
    @Column(name = "evidence_json", nullable = false, columnDefinition = "TEXT") private String evidenceJson;
    @Column(name = "policy_ids_json", nullable = false, columnDefinition = "TEXT") private String policyIdsJson;
    @Column(nullable = false, length = 600) private String explanation;
    @Column(name = "recommended_actions_json", nullable = false, columnDefinition = "TEXT") private String recommendedActionsJson;
    @Column(name = "suggested_reply", nullable = false, length = 1000) private String suggestedReply;
    @Enumerated(EnumType.STRING) @Column(name = "reliability_signal", nullable = false, length = 16) private ReliabilitySignal reliabilitySignal;
    @Column(name = "warnings_json", nullable = false, columnDefinition = "TEXT") private String warningsJson;
    @Column(name = "priority_score", nullable = false) private int priorityScore;
    @Column(name = "applied_rules_json", nullable = false, columnDefinition = "TEXT") private String appliedRulesJson;
    @Enumerated(EnumType.STRING) @Column(name = "decision_source", nullable = false, length = 24) private DecisionSource decisionSource;
    @Column(name = "model_version", nullable = false, length = 80) private String modelVersion;
    @Column(name = "prompt_version", nullable = false, length = 80) private String promptVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    /**
     * A real AI result captured earlier and seeded with the workspace, so the page has reviewed examples before any
     * live call. It has no outbox event, job or model invocation, and live latency and provider figures leave it out.
     */
    @Column(nullable = false) private boolean recorded;

    protected TriageResult() {}

    private TriageResult(UUID eventId, UUID workspaceId, TriageDecision decision, Instant now, ObjectMapper mapper,
            boolean recorded) {
        this.id = UUID.randomUUID();
        this.recorded = recorded;
        if (!recorded) Objects.requireNonNull(eventId, "A live result needs its event id");
        this.eventId = eventId;
        this.workspaceId = Objects.requireNonNull(workspaceId);
        this.ticketId = decision.ticketId();
        this.category = decision.category();
        this.urgency = decision.urgency();
        this.slaRisk = decision.slaRisk();
        this.sentiment = decision.sentiment();
        this.summary = decision.summary();
        this.evidenceJson = json(mapper, decision.evidence());
        this.policyIdsJson = json(mapper, decision.policyIds());
        this.explanation = decision.explanation();
        this.recommendedActionsJson = json(mapper, decision.recommendedActions());
        this.suggestedReply = decision.suggestedReply();
        this.reliabilitySignal = decision.reliabilitySignal();
        this.warningsJson = json(mapper, decision.warnings());
        this.priorityScore = decision.priorityScore();
        this.appliedRulesJson = json(mapper, decision.appliedRules());
        this.decisionSource = decision.decisionSource();
        this.modelVersion = decision.modelVersion();
        this.promptVersion = decision.promptVersion();
        this.createdAt = Objects.requireNonNull(now);
    }

    public static TriageResult from(UUID eventId, UUID workspaceId, TriageDecision decision, Instant now, ObjectMapper mapper) {
        return new TriageResult(eventId, workspaceId, decision, now, mapper, false);
    }

    /** A recorded result: no event id, and marked recorded so live figures leave it out. */
    public static TriageResult recorded(UUID workspaceId, TriageDecision decision, Instant now, ObjectMapper mapper) {
        return new TriageResult(null, workspaceId, decision, now, mapper, true);
    }

    private String json(ObjectMapper mapper, Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("Could not serialize triage result", exception); }
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getTicketId() { return ticketId; }
    public Category getCategory() { return category; }
    public Urgency getUrgency() { return urgency; }
    public SlaRisk getSlaRisk() { return slaRisk; }
    public Sentiment getSentiment() { return sentiment; }
    public String getSummary() { return summary; }
    public String getEvidenceJson() { return evidenceJson; }
    public String getPolicyIdsJson() { return policyIdsJson; }
    public String getExplanation() { return explanation; }
    public String getRecommendedActionsJson() { return recommendedActionsJson; }
    public String getSuggestedReply() { return suggestedReply; }
    public ReliabilitySignal getReliabilitySignal() { return reliabilitySignal; }
    public String getWarningsJson() { return warningsJson; }
    public int getPriorityScore() { return priorityScore; }
    public String getAppliedRulesJson() { return appliedRulesJson; }
    public DecisionSource getDecisionSource() { return decisionSource; }
    public String getModelVersion() { return modelVersion; }
    public String getPromptVersion() { return promptVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isRecorded() { return recorded; }
}
