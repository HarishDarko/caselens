package com.harishdarko.caselens.triage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class PriorityScorer {
    private final PackRules pack = PackRules.evCharging();

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
        score += indicator(rules, text, "PAYMENT_IMPACT", 10);
        score += indicator(rules, text, "SERVICE_BLOCKED", 10);
        score += indicator(rules, text, "BROAD_IMPACT", 15);
        if (context.repeatContactCount() >= 2) {
            score += apply(rules, "REPEAT_CONTACT", 10, "Customer contacted support at least twice");
        }
        if (context.category().equals(pack.generalCategory())) score += indicator(rules, text, "POSITIVE_GENERAL_REQUEST", -10);
        return new PriorityResult(Math.max(0, Math.min(100, score)), rules);
    }

    private int apply(List<RuleApplication> rules, String code, int points, String explanation) {
        rules.add(new RuleApplication(code, points, explanation));
        return points;
    }

    private int indicator(List<RuleApplication> rules, String text, String code, int points) {
        PackRules.PriorityIndicator indicator = pack.priority(code);
        return PackRules.containsAny(text, indicator.terms()) ? apply(rules, code, points, indicator.explanation()) : 0;
    }
}
