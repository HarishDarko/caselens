package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.harishdarko.caselens.ticket.TicketChannel;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The rules provider quotes the ticket as evidence; a quote must fit EvidenceItem's limits and stay verbatim. */
class RuleBasedEvidenceTest {
    private final RuleBasedTriageProvider rules = new RuleBasedTriageProvider("rules-v1", "triage-v1");
    private final TicketRedactor redactor = new TicketRedactor();

    @Test
    void cutsAFirstSentenceLongerThanTheQuoteLimitInsteadOfFailingTheJob() {
        String sentence = "The charger " + "x".repeat(401 - "The charger ".length() - 1) + ".";
        assertThat(sentence).hasSize(401);
        String message = sentence + " Second sentence follows here.";

        ProviderTriageResult result = analyze("Charger keeps failing", message);

        assertThat(result.evidence()).hasSize(2);
        EvidenceItem quote = result.evidence().get(1);
        assertThat(quote.quote()).hasSize(400);
        assertThat(message).startsWith(quote.quote());
    }

    @Test
    void trimsQuotesAndSkipsOnesThatAreBlankOrTooShort() {
        ProviderTriageResult padded = analyze("  Charger offline  ", "   Charger CHG-1 is offline.   More detail here.  ");
        assertThat(padded.evidence()).extracting(EvidenceItem::quote)
                .containsExactly("Charger offline", "Charger CHG-1 is offline.");

        ProviderTriageResult shortMessage = analyze("Charger offline", "ok" + " ".repeat(25));
        assertThat(shortMessage.evidence()).extracting(EvidenceItem::quote).containsExactly("Charger offline");
    }

    private ProviderTriageResult analyze(String subject, String message) {
        TriageRequest request = new TriageRequest(UUID.randomUUID(), subject, message, TicketChannel.WEB, 0);
        return rules.analyze(request, redactor.redact(subject, message)).result();
    }
}
