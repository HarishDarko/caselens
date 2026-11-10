package com.harishdarko.caselens.triage;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class SemanticValidator {
    private final PolicyCatalog policies;
    private final PackRules pack = PackRules.evCharging();

    public SemanticValidator(PolicyCatalog policies) {
        this.policies = Objects.requireNonNull(policies, "Policy catalog is required");
    }

    public ValidatedProviderResult validate(ProviderTriageResult result, RedactedTicket ticket) {
        Objects.requireNonNull(result, "Provider result is required");
        Objects.requireNonNull(ticket, "Redacted ticket is required");
        for (String policyId : result.policyIds()) {
            if (!policies.contains(policyId)) throw new SemanticValidationException("Provider returned an unknown policy identifier");
        }
        String subject = normalize(ticket.subject());
        String message = normalize(ticket.message());
        Set<String> distinctQuotes = new LinkedHashSet<>();
        for (EvidenceItem item : result.evidence()) {
            String quote = normalize(item.quote());
            if (!subject.contains(quote) && !message.contains(quote)) {
                throw new SemanticValidationException("Evidence quote was not found in the redacted ticket");
            }
            distinctQuotes.add(quote);
        }
        if (distinctQuotes.isEmpty()) throw new SemanticValidationException("Provider returned no evidence");
        String evidenceText = String.join(" ", distinctQuotes).toLowerCase(Locale.ROOT);
        ReliabilitySignal signal = distinctQuotes.size() >= 2 && supportsDecision(result, evidenceText)
                ? ReliabilitySignal.HIGH : ReliabilitySignal.MEDIUM;
        return new ValidatedProviderResult(result, signal);
    }

    private boolean supportsDecision(ProviderTriageResult result, String evidenceText) {
        boolean categorySupported = pack.evidenceSupports(result.category(), evidenceText);
        boolean urgencySupported = pack.evidenceSupports(result.urgency(), evidenceText);
        return categorySupported && urgencySupported;
    }

    static String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }
}
