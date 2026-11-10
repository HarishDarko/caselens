package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.harishdarko.caselens.demo.DemoScenario;
import com.harishdarko.caselens.demo.ScenarioCatalog;
import com.harishdarko.caselens.ticket.TicketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins every output of the EV rules (rules fallback provider, semantic validator, priority scorer) on the 9 demo
 * scenarios and on a corpus built from every keyword the three classes use, alone and in ordered pairs, so a
 * change in keyword order or content shows up. The digest was taken on main before the vocabulary moved into the
 * EV pack file. Set -Dcaselens.golden.out=<file> to write the full golden text for a diff.
 */
class EvRulesCharacterizationTest {
    private static final String EXPECTED_SHA256 = "9c7e6f61234635ad7fa0e6bb8f54eba1f577fc8eafe5abe79fa12668e48fb088";
    private static final List<String> TERMS = List.of("refund", "charged", "payment", "billing", "receipt", "authorization",
            "rfid", "account", "login", "sign in", "heartbeat", "network", "offline", "connectivity", "last-seen", "connector",
            "charger", "hardware", "plug", "session", "charging", "charge did not", "never started", "feature", "suggestion",
            "how do", "question", "safety", "fire", "smoke", "site-wide", "outage", "multiple users", "blocked",
            "did not start", "charged twice", "payment captured", "furious", "angry", "unacceptable", "again", "still not",
            "failed", "cannot", "can't", "unable", "error", "never", "thanks", "thank you", "works well", "no session",
            "session blocked", "duplicate", "fault", "charge", "start", "works", "help", "issue", "problem", "delay",
            "captured", "did not", "multiple", "duplicate charge", "charging session blocked", "authorization blocked",
            "authorization rejected", "multiple drivers", "everything works", "feature request", "nothing relevant");

    private final RulesFallbackProvider rules = new RulesFallbackProvider();
    private final TicketRedactor redactor = new TicketRedactor();
    private final SemanticValidator validator = new SemanticValidator(PolicyCatalog.defaultCatalog());
    private final PriorityScorer scorer = new PriorityScorer();

    @Test
    void rulesAgreementOnTheEightSeededEvScenariosIsUnchanged() {
        int evaluated = 0, category = 0, urgency = 0, both = 0;
        for (DemoScenario scenario : scenarios()) {
            if (scenario.key().equals("provider-retry-demo")) continue;
            ProviderTriageResult result = analyze(scenario.subject(), scenario.message());
            boolean c = result.category().name().equals(scenario.expectedCategory());
            boolean u = result.urgency().name().equals(scenario.expectedUrgency());
            evaluated++;
            if (c) category++;
            if (u) urgency++;
            if (c && u) both++;
        }
        assertThat(List.of(evaluated, category, urgency, both)).containsExactly(8, 4, 4, 2);
    }

    @Test
    void everyRulesValidatorAndScorerOutputMatchesTheGolden() throws Exception {
        StringBuilder golden = new StringBuilder();
        for (DemoScenario scenario : scenarios()) {
            describe(golden, "scenario " + scenario.key(), scenario.subject(), scenario.message(), true);
        }
        for (String term : TERMS) {
            describe(golden, "term " + term, "Support: " + term, "Customer reports " + term + ". More detail follows.", true);
        }
        for (String first : TERMS) {
            for (String second : TERMS) {
                describe(golden, "pair " + first + " + " + second, "Support: " + first,
                        "Customer reports " + second + ". More detail follows.", false);
            }
        }

        String text = golden.toString();
        String out = System.getProperty("caselens.golden.out");
        if (out != null) Files.writeString(Path.of(out), text, StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        System.out.println("EV-RULES-GOLDEN sha256=" + digest + " chars=" + text.length() + " lines=" + text.lines().count());
        assertThat(digest).isEqualTo(EXPECTED_SHA256);
    }

    private void describe(StringBuilder golden, String label, String subject, String message, boolean allScores) {
        RedactedTicket redacted = redactor.redact(subject, message);
        ProviderTriageResult result = analyze(subject, message);
        golden.append("## ").append(label).append('\n').append("rules=").append(result).append('\n');
        golden.append("validated=").append(validate(result, redacted)).append('\n');
        String text = subject + "\n" + message;
        golden.append("priority=").append(scorer.score(new PriorityContext(result.urgency(), result.slaRisk(), result.category(), text, 0)))
                .append('\n');
        for (Category category : PackRules.evCharging().categories()) {
            for (Urgency urgency : Urgency.values()) {
                ProviderTriageResult decision = new ProviderTriageResult(category, urgency, result.slaRisk(), result.sentiment(),
                        result.summary(), result.evidence(), result.policyIds(), result.explanation(), result.recommendedActions(),
                        result.suggestedReply(), result.warnings(), result.modelVersion(), result.promptVersion());
                golden.append("validated ").append(category).append('/').append(urgency).append('=')
                        .append(validate(decision, redacted)).append('\n');
                if (!allScores) continue;
                for (SlaRisk slaRisk : SlaRisk.values()) {
                    for (int repeats : new int[] {0, 2}) {
                        golden.append("priority ").append(category).append('/').append(urgency).append('/').append(slaRisk)
                                .append('/').append(repeats).append('=')
                                .append(scorer.score(new PriorityContext(urgency, slaRisk, category, text, repeats))).append('\n');
                    }
                }
            }
        }
    }

    private ProviderTriageResult analyze(String subject, String message) {
        TriageRequest request = new TriageRequest(UUID.fromString("00000000-0000-0000-0000-000000000005"), subject, message,
                TicketChannel.WEB, 0);
        return rules.analyze(request, redactor.redact(subject, message)).result();
    }

    private String validate(ProviderTriageResult result, RedactedTicket redacted) {
        try {
            return validator.validate(result, redacted).reliabilitySignal().name();
        } catch (SemanticValidationException exception) {
            return "rejected: " + exception.getMessage();
        }
    }

    private List<DemoScenario> scenarios() {
        List<DemoScenario> all = new ArrayList<>(ScenarioCatalog.fromClasspath("demo/scenarios-v1.json").all());
        all.sort(Comparator.comparing(DemoScenario::key));
        return all;
    }
}
