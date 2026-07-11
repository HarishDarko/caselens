package com.harishdarko.caselens.triage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class RuleBasedTriageProvider implements TriageProvider {
    private final String modelVersion;
    private final String promptVersion;

    RuleBasedTriageProvider(String modelVersion, String promptVersion) {
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
    }

    @Override
    public ProviderCall analyze(TriageRequest request, RedactedTicket ticket) {
        String subject = ticket.subject();
        String message = ticket.message();
        String text = (subject + "\n" + message).toLowerCase(Locale.ROOT);
        Category category = category(text);
        Urgency urgency = urgency(text, category);
        SlaRisk slaRisk = slaRisk(text, category);
        Sentiment sentiment = sentiment(text);
        List<EvidenceItem> evidence = evidence(subject, message, text, category);
        List<String> policyIds = policies(text, category);
        String summary = summary(category, urgency);
        String explanation = "The ticket evidence indicates " + category.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                + " impact with " + urgency.name().toLowerCase(Locale.ROOT) + " urgency and "
                + slaRisk.name().toLowerCase(Locale.ROOT) + " SLA risk.";
        List<String> actions = actions(category);
        String reply = "Thanks for the detail. We are reviewing the affected service state and will share the next update after verification.";
        return new ProviderCall(new ProviderTriageResult(category, urgency, slaRisk, sentiment, summary, evidence, policyIds,
                explanation, actions, reply, List.of(), modelVersion, promptVersion), null, null);
    }

    private Category category(String text) {
        if (containsAny(text, "refund", "charged", "payment", "billing", "receipt")) return Category.BILLING;
        if (containsAny(text, "authorization", "rfid", "account", "login", "sign in")) return Category.ACCOUNT_ACCESS;
        if (containsAny(text, "heartbeat", "network", "offline", "connectivity", "last-seen")) return Category.CONNECTIVITY;
        if (containsAny(text, "connector", "charger", "hardware", "plug")) return Category.CHARGER_HARDWARE;
        if (containsAny(text, "session", "charging", "charge did not", "never started")) return Category.CHARGING_SESSION;
        if (containsAny(text, "feature", "suggestion", "how do", "question")) return Category.GENERAL;
        return Category.GENERAL;
    }

    private Urgency urgency(String text, Category category) {
        if (containsAny(text, "safety", "fire", "smoke", "site-wide", "outage", "multiple users")) return Urgency.CRITICAL;
        if (containsAny(text, "blocked", "never started", "did not start", "charged twice", "payment captured")) return Urgency.HIGH;
        if (category == Category.GENERAL) return Urgency.LOW;
        return Urgency.MEDIUM;
    }

    private SlaRisk slaRisk(String text, Category category) {
        if (containsAny(text, "site-wide", "outage", "multiple users", "payment captured", "blocked")) return SlaRisk.HIGH;
        return category == Category.GENERAL ? SlaRisk.LOW : SlaRisk.MEDIUM;
    }

    private Sentiment sentiment(String text) {
        if (containsAny(text, "furious", "angry", "unacceptable", "again", "still not")) return Sentiment.ANGRY;
        if (containsAny(text, "failed", "cannot", "can't", "unable", "error", "never")) return Sentiment.NEGATIVE;
        if (containsAny(text, "thanks", "thank you", "works well")) return Sentiment.POSITIVE;
        return Sentiment.NEUTRAL;
    }

    private List<EvidenceItem> evidence(String subject, String message, String text, Category category) {
        List<EvidenceItem> result = new ArrayList<>();
        result.add(new EvidenceItem(subject, "The subject identifies the reported support impact."));
        String sentence = message.split("(?<=[.!?])\\s+", 2)[0];
        if (sentence.length() >= 3 && !sentence.equals(subject)) result.add(new EvidenceItem(sentence, "The message provides direct operational evidence."));
        return result.subList(0, Math.min(result.size(), 2));
    }

    private List<String> policies(String text, Category category) {
        List<String> ids = new ArrayList<>();
        if (containsAny(text, "payment", "charged")
                && containsAny(text, "no session", "did not start", "never started", "session blocked")) {
            ids.add("PAYMENT_CAPTURE_NO_SESSION");
        }
        if (containsAny(text, "charged twice", "duplicate")) ids.add("DUPLICATE_CHARGE_REVIEW");
        if (containsAny(text, "site-wide", "outage", "multiple users")) ids.add("CHARGER_SITE_OUTAGE");
        if (category == Category.CHARGER_HARDWARE && containsAny(text, "connector", "fault")) ids.add("CONNECTOR_FAULT");
        if (category == Category.CONNECTIVITY) ids.add("OCPP_CONNECTIVITY");
        if (category == Category.ACCOUNT_ACCESS) ids.add("ACCOUNT_AUTHORIZATION");
        return ids.subList(0, Math.min(ids.size(), 3));
    }

    private String summary(Category category, Urgency urgency) {
        return "Synthetic " + category.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " case requires "
                + urgency.name().toLowerCase(Locale.ROOT) + " review.";
    }

    private List<String> actions(Category category) {
        return switch (category) {
            case BILLING -> List.of("Verify payment and session state", "Collect a transaction reference for billing review");
            case CHARGER_HARDWARE -> List.of("Isolate the connector fault", "Check alternate connector availability");
            case CONNECTIVITY -> List.of("Check heartbeat and last-seen timestamp", "Verify the network path");
            case ACCOUNT_ACCESS -> List.of("Verify account or RFID authorization state", "Do not request secrets");
            case CHARGING_SESSION -> List.of("Verify the session lifecycle state", "Check whether another connector is available");
            case REFUND -> List.of("Verify the captured payment state", "Escalate for refund review after confirmation");
            case GENERAL -> List.of("Clarify the requested outcome", "Respond with the next supported step");
        };
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }
}
