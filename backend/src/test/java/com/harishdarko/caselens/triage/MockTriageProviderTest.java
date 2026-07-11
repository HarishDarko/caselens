package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.harishdarko.caselens.ticket.TicketChannel;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MockTriageProviderTest {
    @Test
    void producesStableExplainableOutputFromSyntheticTicketText() {
        MockTriageProvider provider = new MockTriageProvider();
        TriageRequest request = new TriageRequest(UUID.randomUUID(), "Payment accepted but no session",
                "Payment ID [PAYMENT_ID_1] was captured, but session SESSION-DEMO-88421 never started.",
                TicketChannel.WEB, 0);
        RedactedTicket redacted = new RedactedTicket(request.subject(), request.message());

        ProviderCall first = provider.analyze(request, redacted);
        ProviderCall second = provider.analyze(request, redacted);

        assertThat(first).isEqualTo(second);
        assertThat(first.result().category()).isEqualTo(Category.BILLING);
        assertThat(first.result().evidence()).isNotEmpty();
        assertThat(first.result().policyIds()).contains("PAYMENT_CAPTURE_NO_SESSION");
    }

    @Test
    void doesNotAttachPaymentSessionPolicyToAnOrdinaryPaymentQuestion() {
        MockTriageProvider provider = new MockTriageProvider();
        TriageRequest request = new TriageRequest(UUID.randomUUID(), "Payment receipt question",
                "I have a question about where to find my payment receipt in the account.", TicketChannel.WEB, 0);

        ProviderCall result = provider.analyze(request, new RedactedTicket(request.subject(), request.message()));

        assertThat(result.result().policyIds()).doesNotContain("PAYMENT_CAPTURE_NO_SESSION");
    }
}
