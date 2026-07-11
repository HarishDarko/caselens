package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TriageDomainContractTest {
    @Test
    void providerResultDefensivelyCopiesCollectionsAndRejectsOutOfBoundsText() {
        List<EvidenceItem> evidence = new ArrayList<>(List.of(
                new EvidenceItem("Payment accepted", "Payment state")));
        ProviderTriageResult result = new ProviderTriageResult(
                Category.BILLING, Urgency.HIGH, SlaRisk.HIGH, Sentiment.NEGATIVE,
                "Payment completed but the session did not begin.", evidence,
                List.of("PAYMENT_CAPTURE_NO_SESSION"),
                "The payment was captured while service remained unavailable to the driver.",
                List.of("Verify payment and session state"),
                "We are checking the payment and session state now.", List.of(), "mock-v1", "triage-v1");

        evidence.clear();

        assertThat(result.evidence()).hasSize(1);
        assertThatThrownBy(() -> new EvidenceItem("", "meaning"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderTriageResult(
                Category.BILLING, Urgency.HIGH, SlaRisk.HIGH, Sentiment.NEGATIVE,
                "too short", List.of(new EvidenceItem("Payment accepted", "Payment state")), List.of(),
                "The payment was captured while service remained unavailable to the driver.",
                List.of("Verify payment and session state"),
                "We are checking the payment and session state now.", List.of(), "mock-v1", "triage-v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
