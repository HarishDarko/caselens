package com.harishdarko.caselens.triage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * One real model answer captured earlier for a seeded demo ticket. The answer, its reliability and its capture time
 * are stored exactly as captured; the priority score and the rules that produced it are computed again for every
 * workspace by {@link PriorityScorer}, so they cannot drift from the scorer. {@code scenarioSha256} fingerprints the
 * ticket text the model answered, so an answer is never shown on a ticket whose text has since changed.
 */
public record RecordedTriage(String scenarioKey, String scenarioSha256, Instant capturedAt, ProviderTriageResult answer,
        ReliabilitySignal reliabilitySignal) {
    public RecordedTriage {
        Objects.requireNonNull(scenarioKey, "Scenario key is required");
        Objects.requireNonNull(scenarioSha256, "Scenario fingerprint is required");
        Objects.requireNonNull(capturedAt, "Capture time is required");
        Objects.requireNonNull(answer, "Answer is required");
        Objects.requireNonNull(reliabilitySignal, "Reliability signal is required");
    }

    /** The fingerprint of a ticket's text: SHA-256 of the subject, a newline and the message. */
    public static String fingerprint(String subject, String message) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((subject + "\n" + message).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    /** True when the ticket text is the text the model answered. */
    public boolean matches(String subject, String message) {
        return scenarioSha256.equals(fingerprint(subject, message));
    }

    /** The stored decision for this ticket, with its score and rules computed as a live result's are. */
    public TriageDecision toDecision(UUID ticketId, String subject, String message, PackRules pack,
            PriorityScorer scorer) {
        PriorityResult priority = scorer.score(new PriorityContext(answer.urgency(), answer.slaRisk(), answer.category(),
                subject + "\n" + message, 0, pack));
        return new TriageDecision(ticketId, answer.category(), answer.urgency(), answer.slaRisk(), answer.sentiment(),
                answer.summary(), answer.evidence(), answer.policyIds(), answer.explanation(),
                answer.recommendedActions(), answer.suggestedReply(), reliabilitySignal, answer.warnings(),
                priority.score(), priority.appliedRules(), DecisionSource.AI_VALIDATED, answer.modelVersion(),
                answer.promptVersion());
    }
}
