package com.harishdarko.caselens.triage;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class SemanticValidator {
    private final PolicyCatalog policies;

    public SemanticValidator(PolicyCatalog policies) {
        this.policies = Objects.requireNonNull(policies, "Policy catalog is required");
    }

    /** Validates against this validator's policy catalog and the default industry pack. */
    public ValidatedProviderResult validate(ProviderTriageResult result, RedactedTicket ticket) {
        return validate(result, ticket, policies, PackRules.evCharging());
    }

    /** Validates against the workspace's industry pack: its categories, its policies and its vocabulary. */
    public ValidatedProviderResult validate(ProviderTriageResult result, RedactedTicket ticket, PackRules pack) {
        Objects.requireNonNull(pack, "Industry pack is required");
        return validate(result, ticket, pack.policies(), pack);
    }

    private ValidatedProviderResult validate(ProviderTriageResult result, RedactedTicket ticket, PolicyCatalog policies,
            PackRules pack) {
        Objects.requireNonNull(result, "Provider result is required");
        Objects.requireNonNull(ticket, "Redacted ticket is required");
        if (!pack.categories().contains(result.category())) {
            throw new SemanticValidationException("Provider returned a category outside the industry pack");
        }
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
        ReliabilitySignal signal = distinctQuotes.size() >= 2 && supportsDecision(pack, result, evidenceText)
                ? ReliabilitySignal.HIGH : ReliabilitySignal.MEDIUM;
        return new ValidatedProviderResult(result, signal);
    }

    private boolean supportsDecision(PackRules pack, ProviderTriageResult result, String evidenceText) {
        boolean categorySupported = pack.evidenceSupports(result.category(), evidenceText);
        boolean urgencySupported = pack.evidenceSupports(result.urgency(), evidenceText);
        return categorySupported && urgencySupported;
    }

    static String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }
}
