package com.harishdarko.caselens.triage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class RuleBasedTriageProvider implements TriageProvider {
    private static final int MIN_QUOTE_LENGTH = 3;
    private static final int MAX_QUOTE_LENGTH = 400;
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
        PackRules pack = request.pack();
        Category category = category(pack, text);
        Urgency urgency = urgency(pack, text, category);
        SlaRisk slaRisk = slaRisk(pack, text, category);
        Sentiment sentiment = sentiment(pack, text);
        List<EvidenceItem> evidence = evidence(subject, message, text, category);
        List<String> policyIds = policies(pack, text, category);
        String summary = summary(category, urgency);
        String explanation = "The ticket evidence indicates " + category.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                + " impact with " + urgency.name().toLowerCase(Locale.ROOT) + " urgency and "
                + slaRisk.name().toLowerCase(Locale.ROOT) + " SLA risk.";
        List<String> actions = pack.actions(category);
        String reply = "Thanks for the detail. We are reviewing the affected service state and will share the next update after verification.";
        return new ProviderCall(new ProviderTriageResult(category, urgency, slaRisk, sentiment, summary, evidence, policyIds,
                explanation, actions, reply, List.of(), modelVersion, promptVersion), null, null);
    }

    private Category category(PackRules pack, String text) {
        return pack.category(text).orElse(pack.generalCategory());
    }

    private Urgency urgency(PackRules pack, String text, Category category) {
        return pack.urgency(text).orElse(category.equals(pack.generalCategory()) ? Urgency.LOW : Urgency.MEDIUM);
    }

    private SlaRisk slaRisk(PackRules pack, String text, Category category) {
        return pack.slaRisk(text).orElse(category.equals(pack.generalCategory()) ? SlaRisk.LOW : SlaRisk.MEDIUM);
    }

    private Sentiment sentiment(PackRules pack, String text) {
        return pack.sentiment(text).orElse(Sentiment.NEUTRAL);
    }

    private List<EvidenceItem> evidence(String subject, String message, String text, Category category) {
        List<EvidenceItem> result = new ArrayList<>();
        String subjectQuote = quote(subject);
        if (subjectQuote != null) result.add(new EvidenceItem(subjectQuote, "The subject identifies the reported support impact."));
        String sentence = quote(message.strip().split("(?<=[.!?])\\s+", 2)[0]);
        if (sentence != null && !sentence.equals(subjectQuote)) {
            result.add(new EvidenceItem(sentence, "The message provides direct operational evidence."));
        }
        return result.subList(0, Math.min(result.size(), 2));
    }

    /**
     * An evidence quote: the text trimmed and cut to the EvidenceItem limit. Cutting keeps a contiguous run of the
     * ticket text, so the quote stays verbatim. Returns null for text too short to quote, which is skipped.
     */
    private static String quote(String value) {
        String trimmed = value.strip();
        if (trimmed.length() > MAX_QUOTE_LENGTH) trimmed = trimmed.substring(0, MAX_QUOTE_LENGTH).stripTrailing();
        return trimmed.length() < MIN_QUOTE_LENGTH ? null : trimmed;
    }

    private List<String> policies(PackRules pack, String text, Category category) {
        List<String> ids = pack.policyIds(text, category);
        return ids.subList(0, Math.min(ids.size(), 3));
    }

    private String summary(Category category, Urgency urgency) {
        return "Synthetic " + category.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " case requires "
                + urgency.name().toLowerCase(Locale.ROOT) + " review.";
    }
}
