package com.harishdarko.caselens.triage;

import java.time.Instant;
import java.util.UUID;

public record FeedbackSnapshot(UUID id, UUID ticketId, UUID originalResultId, Category correctedCategory,
        Urgency correctedUrgency, SlaRisk correctedSlaRisk, String note, String submittedBy, Instant createdAt) {
    static FeedbackSnapshot from(TriageFeedback feedback) {
        return new FeedbackSnapshot(feedback.getId(), feedback.getTicketId(), feedback.getOriginalResultId(),
                feedback.getCorrectedCategory(), feedback.getCorrectedUrgency(), feedback.getCorrectedSlaRisk(),
                feedback.getNote(), feedback.getSubmittedBy(), feedback.getCreatedAt());
    }
}
