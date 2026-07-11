package com.harishdarko.caselens.triage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class PriorityScorer {
    public PriorityResult score(PriorityContext context) {
        Objects.requireNonNull(context);
        List<RuleApplication> rules = new ArrayList<>();
        int score = switch (context.urgency()) {
            case LOW -> apply(rules, "URGENCY_LOW", 10, "Low urgency baseline");
            case MEDIUM -> apply(rules, "URGENCY_MEDIUM", 25, "Medium urgency baseline");
            case HIGH -> apply(rules, "URGENCY_HIGH", 40, "High urgency baseline");
            case CRITICAL -> apply(rules, "URGENCY_CRITICAL", 55, "Critical urgency baseline");
        };
        score += switch (context.slaRisk()) {
            case LOW -> 0;
            case MEDIUM -> apply(rules, "SLA_RISK_MEDIUM", 10, "Medium risk of breaching the response target");
            case HIGH -> apply(rules, "SLA_RISK_HIGH", 20, "High risk of breaching the response target");
        };

        String text = context.normalizedText().toLowerCase(Locale.ROOT);
        if (containsAny(text, "payment captured", "duplicate charge", "charged twice", "refund")) {
            score += apply(rules, "PAYMENT_IMPACT", 10, "Payment, duplicate charge, or refund impact detected");
        }
        if (containsAny(text, "session blocked", "charging session blocked", "did not start", "never started",
                "no session", "authorization blocked", "authorization rejected")) {
            score += apply(rules, "SERVICE_BLOCKED", 10, "Charging or account authorization is blocked");
        }
        if (containsAny(text, "site-wide", "multiple users", "multiple drivers", "outage", "safety")) {
            score += apply(rules, "BROAD_IMPACT", 15, "Broad service scope, outage, or safety impact detected");
        }
        if (context.repeatContactCount() >= 2) {
            score += apply(rules, "REPEAT_CONTACT", 10, "Customer contacted support at least twice");
        }
        if (context.category() == Category.GENERAL
                && containsAny(text, "thanks", "thank you", "everything works", "feature request")) {
            score += apply(rules, "POSITIVE_GENERAL_REQUEST", -10, "Positive general or feature-only request");
        }
        return new PriorityResult(Math.max(0, Math.min(100, score)), rules);
    }

    private int apply(List<RuleApplication> rules, String code, int points, String explanation) {
        rules.add(new RuleApplication(code, points, explanation));
        return points;
    }

    private boolean containsAny(String text, String... indicators) {
        for (String indicator : indicators) if (text.contains(indicator)) return true;
        return false;
    }
}
