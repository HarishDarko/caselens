package com.harishdarko.caselens.triage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A triage result as every endpoint returns it: the stored fields, plus what the page needs to explain it without its
 * own copy of the industry packs (the category's label and description, the policy version, and each policy's label
 * and guidance, from the workspace's pack), whether it is a recorded result, and why the rules decided it (null when
 * the AI answer was validated). policyIds stays for older clients.
 */
public record TriageResultView(UUID id, UUID ticketId, Category category, Urgency urgency, SlaRisk slaRisk,
        Sentiment sentiment, String summary, JsonNode evidence, JsonNode policyIds, String explanation,
        JsonNode recommendedActions, String suggestedReply, ReliabilitySignal reliabilitySignal, JsonNode warnings,
        int priorityScore, JsonNode appliedRules, DecisionSource decisionSource, String modelVersion,
        String promptVersion, Instant createdAt, FallbackReason fallbackReason, boolean recorded, String categoryLabel,
        String categoryDescription, String policyVersion, List<PolicyView> policies) {

    /** One policy the result cites, as the page shows it. */
    public record PolicyView(String id, String label, String guidance) {}

    public static TriageResultView from(TriageResult result, FallbackReason fallbackReason, PackRules pack,
            ObjectMapper mapper) {
        Objects.requireNonNull(pack, "The workspace's industry pack is required");
        try {
            JsonNode policyIds = mapper.readTree(result.getPolicyIdsJson());
            return new TriageResultView(result.getId(), result.getTicketId(), result.getCategory(), result.getUrgency(),
                    result.getSlaRisk(), result.getSentiment(), result.getSummary(), mapper.readTree(result.getEvidenceJson()),
                    policyIds, result.getExplanation(), mapper.readTree(result.getRecommendedActionsJson()),
                    result.getSuggestedReply(), result.getReliabilitySignal(), mapper.readTree(result.getWarningsJson()),
                    result.getPriorityScore(), mapper.readTree(result.getAppliedRulesJson()), result.getDecisionSource(),
                    result.getModelVersion(), result.getPromptVersion(), result.getCreatedAt(), fallbackReason,
                    result.isRecorded(), orName(pack.categoryLabel(result.getCategory()), result.getCategory().name()),
                    orEmpty(pack.description(result.getCategory())), pack.policyVersion(), policies(policyIds, pack));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored triage result is not readable", exception);
        }
    }

    /**
     * Every cited policy, in the result's order. A policy the pack no longer has (a result stored before a pack
     * change) keeps its id as its label and has no guidance, so the list always matches policyIds.
     */
    private static List<PolicyView> policies(JsonNode policyIds, PackRules pack) {
        List<PolicyView> policies = new ArrayList<>();
        for (JsonNode node : policyIds) {
            String id = node.asText();
            policies.add(new PolicyView(id, orName(pack.policyLabel(id), id), orEmpty(pack.policies().guidance(id))));
        }
        return List.copyOf(policies);
    }

    private static String orName(String label, String name) { return label == null ? name : label; }

    private static String orEmpty(String value) { return value == null ? "" : value; }
}
