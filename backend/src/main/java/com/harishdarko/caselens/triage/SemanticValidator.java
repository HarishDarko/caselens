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
        boolean categorySupported = switch (result.category()) {
            case BILLING -> containsAny(evidenceText, "payment", "charge", "billing", "refund", "receipt");
            case CHARGING_SESSION -> containsAny(evidenceText, "session", "charging", "start", "blocked");
            case CHARGER_HARDWARE -> containsAny(evidenceText, "charger", "connector", "hardware", "fault");
            case CONNECTIVITY -> containsAny(evidenceText, "heartbeat", "network", "offline", "connectivity", "last-seen");
            case ACCOUNT_ACCESS -> containsAny(evidenceText, "account", "authorization", "rfid", "login");
            case REFUND -> containsAny(evidenceText, "refund", "charge", "payment");
            case GENERAL -> containsAny(evidenceText, "question", "feature", "suggestion", "works", "help");
        };
        boolean urgencySupported = switch (result.urgency()) {
            case LOW -> containsAny(evidenceText, "question", "feature", "thanks", "works");
            case MEDIUM -> containsAny(evidenceText, "issue", "problem", "delay", "error");
            case HIGH -> containsAny(evidenceText, "blocked", "never", "failed", "outage", "captured", "unable", "did not");
            case CRITICAL -> containsAny(evidenceText, "safety", "fire", "smoke", "site-wide", "outage", "multiple");
        };
        return categorySupported && urgencySupported;
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    static String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }
}
