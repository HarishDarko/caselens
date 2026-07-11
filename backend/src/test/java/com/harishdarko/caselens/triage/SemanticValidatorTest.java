package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticValidatorTest {
    private final PolicyCatalog policies = PolicyCatalog.of(Set.of("PAYMENT_CAPTURE_NO_SESSION", "CONNECTOR_FAULT"));
    private final SemanticValidator validator = new SemanticValidator(policies);
    private final RedactedTicket ticket = new RedactedTicket(
            "Payment accepted but charging did not start",
            "Payment ID [PAYMENT_ID_1] was captured, but session SESSION-DEMO-88421 never started.");

    @Test
    void acceptsExactEvidenceAndDerivesHighReliabilityFromTwoDistinctItems() {
        ProviderTriageResult result = validResult(List.of(
                new EvidenceItem("Payment ID [PAYMENT_ID_1] was captured", "Payment completed"),
                new EvidenceItem("session SESSION-DEMO-88421 never started", "Charging service is blocked")));

        ValidatedProviderResult validated = validator.validate(result, ticket);

        assertThat(validated.reliabilitySignal()).isEqualTo(ReliabilitySignal.HIGH);
    }

    @Test
    void derivesMediumReliabilityFromOneExactEvidenceItem() {
        ProviderTriageResult result = validResult(List.of(
                new EvidenceItem("Payment ID [PAYMENT_ID_1] was captured", "Payment completed")));

        assertThat(validator.validate(result, ticket).reliabilitySignal()).isEqualTo(ReliabilitySignal.MEDIUM);
    }

    @Test
    void rejectsInventedEvidence() {
        ProviderTriageResult result = validResult(List.of(
                new EvidenceItem("The charger exploded", "Invented safety event")));

        assertThatThrownBy(() -> validator.validate(result, ticket))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessage("Evidence quote was not found in the redacted ticket");
    }

    @Test
    void rejectsUnknownPolicies() {
        ProviderTriageResult result = new ProviderTriageResult(
                Category.CHARGING_SESSION, Urgency.HIGH, SlaRisk.HIGH, Sentiment.NEGATIVE,
                "Payment completed but the charging session did not begin.",
                List.of(new EvidenceItem("session SESSION-DEMO-88421 never started", "Service blocked")),
                List.of("PROMISE_IMMEDIATE_REFUND"),
                "Payment was captured while the expected charging service remained unavailable.",
                List.of("Verify payment and session state"),
                "We are checking the payment and charging session state now.", List.of(), "mock-v1", "triage-v1");

        assertThatThrownBy(() -> validator.validate(result, ticket))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessage("Provider returned an unknown policy identifier");
    }

    @Test
    void downgradesUnrelatedExactQuotesInsteadOfCallingThemHighReliability() {
        ProviderTriageResult result = validResult(List.of(
                new EvidenceItem("Customer said hello", "The customer greeted support."),
                new EvidenceItem("Thanks for the help", "The customer expressed thanks.")));

        assertThat(validator.validate(result, new RedactedTicket(
                "Customer said hello", "Thanks for the help and have a nice day."))
                .reliabilitySignal()).isEqualTo(ReliabilitySignal.MEDIUM);
    }

    private ProviderTriageResult validResult(List<EvidenceItem> evidence) {
        return new ProviderTriageResult(
                Category.CHARGING_SESSION, Urgency.HIGH, SlaRisk.HIGH, Sentiment.NEGATIVE,
                "Payment completed but the charging session did not begin.", evidence,
                List.of("PAYMENT_CAPTURE_NO_SESSION"),
                "Payment was captured while the expected charging service remained unavailable.",
                List.of("Verify payment and session state"),
                "We are checking the payment and charging session state now.", List.of(), "mock-v1", "triage-v1");
    }
}
