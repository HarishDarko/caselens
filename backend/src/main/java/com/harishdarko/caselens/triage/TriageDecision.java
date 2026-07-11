package com.harishdarko.caselens.triage;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record TriageDecision(
        UUID ticketId,
        Category category,
        Urgency urgency,
        SlaRisk slaRisk,
        Sentiment sentiment,
        String summary,
        List<EvidenceItem> evidence,
        List<String> policyIds,
        String explanation,
        List<String> recommendedActions,
        String suggestedReply,
        ReliabilitySignal reliabilitySignal,
        List<String> warnings,
        int priorityScore,
        List<RuleApplication> appliedRules,
        DecisionSource decisionSource,
        String modelVersion,
        String promptVersion) {
    public TriageDecision {
        Objects.requireNonNull(ticketId, "Ticket id is required");
        Objects.requireNonNull(category, "Category is required");
        Objects.requireNonNull(urgency, "Urgency is required");
        Objects.requireNonNull(slaRisk, "SLA risk is required");
        Objects.requireNonNull(sentiment, "Sentiment is required");
        Objects.requireNonNull(summary, "Summary is required");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "Evidence is required"));
        policyIds = List.copyOf(Objects.requireNonNull(policyIds, "Policy identifiers are required"));
        Objects.requireNonNull(explanation, "Explanation is required");
        recommendedActions = List.copyOf(Objects.requireNonNull(recommendedActions, "Recommended actions are required"));
        Objects.requireNonNull(suggestedReply, "Suggested reply is required");
        Objects.requireNonNull(reliabilitySignal, "Reliability signal is required");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "Warnings are required"));
        if (priorityScore < 0 || priorityScore > 100) throw new IllegalArgumentException("Priority score must be 0 to 100");
        appliedRules = List.copyOf(Objects.requireNonNull(appliedRules, "Applied rules are required"));
        Objects.requireNonNull(decisionSource, "Decision source is required");
        Objects.requireNonNull(modelVersion, "Model version is required");
        Objects.requireNonNull(promptVersion, "Prompt version is required");
    }
}
