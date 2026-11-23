package com.harishdarko.caselens.triage;

import java.time.Instant;
import java.util.UUID;

/**
 * The triage result columns that the evaluation summary and the operations overview read on every live poll. It
 * leaves out the summary, explanation, reply and JSON text columns of TriageResult, which those views never show.
 */
public record ResultFacts(UUID id, UUID eventId, UUID ticketId, Category category, Urgency urgency, SlaRisk slaRisk,
        DecisionSource decisionSource, String modelVersion, Instant createdAt) {
    public static ResultFacts of(TriageResult result) {
        return new ResultFacts(result.getId(), result.getEventId(), result.getTicketId(), result.getCategory(),
                result.getUrgency(), result.getSlaRisk(), result.getDecisionSource(), result.getModelVersion(),
                result.getCreatedAt());
    }
}
