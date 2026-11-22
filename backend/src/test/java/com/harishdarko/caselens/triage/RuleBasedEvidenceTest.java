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

    @Test
    void alwaysQuotesAtLeastOneEvidenceItemWhenTheSubjectAndFirstSentenceAreTooShort() {
        // CL2-02 probe: a padded subject under 3 visible characters and a message whose first "sentence" is "1.".
        String message = "1. Every charger at the site shows offline since this morning.";
        ProviderTriageResult numbered = analyze("  OK  ", message);
        assertThat(numbered.evidence()).extracting(EvidenceItem::quote).containsExactly(message);

        ProviderTriageResult lettered = analyze("Hi   ", "A. B is broken and the screen stays dark.");
        assertThat(lettered.evidence()).extracting(EvidenceItem::quote)
                .containsExactly("A. B is broken and the screen stays dark.");

        // Nothing quotable after stripping either field: the raw padded subject is still verbatim ticket text.
        ProviderTriageResult tiny = analyze("  OK  ", "ok" + " ".repeat(25));
        assertThat(tiny.evidence()).extracting(EvidenceItem::quote).containsExactly("  OK  ");
    }

    @Test
    void theRulesFallbackNoLongerFailsTheJobOnTheProbeTicket() {
        TriageEngine engine = new TriageEngine((request, ticket) -> {
            throw new ProviderCallException("down", "PROVIDER_IO");
        }, new RulesFallbackProvider(), new SemanticValidator(PolicyCatalog.defaultCatalog()), new PriorityScorer());

        TriageDecision decision = engine.triage(new TriageRequest(UUID.randomUUID(), "  OK  ",
                "1. Every charger at the site shows offline since this morning.", TicketChannel.WEB, 0));

        assertThat(decision.decisionSource()).isEqualTo(DecisionSource.RULES_FALLBACK);
        assertThat(decision.evidence()).hasSize(1);
    }

    private ProviderTriageResult analyze(String subject, String message) {
        TriageRequest request = new TriageRequest(UUID.randomUUID(), subject, message, TicketChannel.WEB, 0);
        return rules.analyze(request, redactor.redact(subject, message)).result();
    }
}
