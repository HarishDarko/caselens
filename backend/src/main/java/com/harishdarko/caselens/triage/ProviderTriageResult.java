package com.harishdarko.caselens.triage;

import java.util.List;
import java.util.Objects;

public record ProviderTriageResult(
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
        List<String> warnings,
        String modelVersion,
        String promptVersion) {

    public ProviderTriageResult {
        category = Objects.requireNonNull(category, "Category is required");
        urgency = Objects.requireNonNull(urgency, "Urgency is required");
        slaRisk = Objects.requireNonNull(slaRisk, "SLA risk is required");
        sentiment = Objects.requireNonNull(sentiment, "Sentiment is required");
        summary = bounded(summary, "Summary", 20, 300);
        evidence = immutableBounded(evidence, "Evidence", 1, 5);
        policyIds = immutableBounded(policyIds, "Policy identifiers", 0, 3);
        explanation = bounded(explanation, "Explanation", 30, 600);
        recommendedActions = immutableBounded(recommendedActions, "Recommended actions", 1, 5);
        suggestedReply = bounded(suggestedReply, "Suggested reply", 20, 1000);
        warnings = immutableBounded(warnings, "Warnings", 0, 5);
        modelVersion = bounded(modelVersion, "Model version", 1, 80);
        promptVersion = bounded(promptVersion, "Prompt version", 1, 80);
    }

    private static String bounded(String value, String label, int minimum, int maximum) {
        Objects.requireNonNull(value, label + " is required");
        if (value.isBlank() || value.length() < minimum || value.length() > maximum) {
            throw new IllegalArgumentException(label + " must contain " + minimum + " to " + maximum + " characters");
        }
        return value;
    }

    private static <T> List<T> immutableBounded(List<T> value, String label, int minimum, int maximum) {
        Objects.requireNonNull(value, label + " are required");
        if (value.size() < minimum || value.size() > maximum || value.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(label + " must contain " + minimum + " to " + maximum + " values");
        }
        return List.copyOf(value);
    }
}
