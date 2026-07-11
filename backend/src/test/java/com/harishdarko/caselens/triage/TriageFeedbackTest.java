package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TriageFeedbackTest {
    private static final Instant NOW = Instant.parse("2026-07-11T15:00:00Z");

    @Test
    void storesAnAppendOnlyCorrectionWithoutChangingTheOriginalResult() {
        UUID workspaceId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        TriageResult original = TriageResult.from(UUID.randomUUID(), workspaceId,
                new TriageDecision(ticketId, Category.CHARGING_SESSION, Urgency.MEDIUM, SlaRisk.MEDIUM,
                        Sentiment.NEGATIVE, "Charging session did not start", List.of(), List.of("POL-CHARGE-01"),
                        "The session did not start after payment.", List.of("Inspect the charger session"),
                        "We are reviewing the charging session.", ReliabilitySignal.HIGH, List.of(), 55, List.of(),
                        DecisionSource.AI_VALIDATED, "mock-v1", "triage-v1"), NOW, new ObjectMapper());

        TriageFeedback feedback = TriageFeedback.submit(UUID.randomUUID(), workspaceId, ticketId, original.getId(),
                Category.CHARGING_SESSION, Urgency.HIGH, null, "Reviewer confirmed high urgency", "demo-reviewer", NOW);

        assertThat(feedback.getOriginalResultId()).isEqualTo(original.getId());
        assertThat(feedback.getCorrectedUrgency()).isEqualTo(Urgency.HIGH);
        assertThat(feedback.differsFrom(original)).isTrue();
        assertThat(original.getUrgency()).isEqualTo(Urgency.MEDIUM);
    }

    @Test
    void requiresAtLeastOneCorrectionField() {
        assertThatIllegalArgumentException().isThrownBy(() -> TriageFeedback.submit(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, null, null, "No correction", "demo-reviewer", NOW));
    }
}
