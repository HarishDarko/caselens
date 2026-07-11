package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.harishdarko.caselens.ticket.TicketChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TriageEngineTest {
    private final TriageRequest request = new TriageRequest(UUID.randomUUID(), "Charger failed",
            "The charger connector failed and charging never started for this session.", TicketChannel.WEB, 0);

    @Test
    void fallsBackWithLowReliabilityAndWarningWhenProviderOutputIsInvalid() {
        AtomicReference<ModelInvocationRecord> invocation = new AtomicReference<>();
        TriageProvider invalid = (ignoredRequest, ignoredTicket) -> new ProviderCall(
                new ProviderTriageResult(Category.CHARGER_HARDWARE, Urgency.HIGH, SlaRisk.HIGH, Sentiment.NEGATIVE,
                        "The provider returned an invalid evidence quote.",
                        java.util.List.of(new EvidenceItem("Invented evidence", "Not in ticket")), java.util.List.of(),
                        "The provider output cannot be trusted because its evidence is not grounded in the ticket.",
                        java.util.List.of("Verify the charger state"),
                        "We are reviewing the charger state and will update you after verification.",
                        java.util.List.of(), "invalid-v1", "triage-v1"), null, null);
        TriageEngine engine = new TriageEngine(new TicketRedactor(), invalid, new RulesFallbackProvider(),
                new SemanticValidator(PolicyCatalog.defaultCatalog()), new PriorityScorer(), invocation::set,
                Clock.fixed(Instant.parse("2026-07-11T14:00:00Z"), ZoneOffset.UTC));

        TriageDecision decision = engine.triage(request);

        assertThat(decision.decisionSource()).isEqualTo(DecisionSource.RULES_FALLBACK);
        assertThat(decision.reliabilitySignal()).isEqualTo(ReliabilitySignal.LOW);
        assertThat(decision.warnings()).contains("AI provider result unavailable or invalid");
        assertThat(invocation.get().status()).isEqualTo("FAILURE");
        assertThat(invocation.get().sanitizedErrorCode()).isEqualTo("SEMANTIC_VALIDATION");
        assertThat(invocation.get().toString()).doesNotContain(request.message());
    }

    @Test
    void doesNotTurnAValidDecisionIntoFallbackWhenInvocationRecordingFails() {
        TriageEngine engine = new TriageEngine(new TicketRedactor(), new MockTriageProvider(), new RulesFallbackProvider(),
                new SemanticValidator(PolicyCatalog.defaultCatalog()), new PriorityScorer(), ignored -> {
                    throw new IllegalStateException("database unavailable");
                }, Clock.systemUTC());

        assertThat(engine.triage(request).decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
    }

    @Test
    void recordsConfiguredProviderMetadataWhenThePrimaryProviderFails() {
        AtomicReference<ModelInvocationRecord> invocation = new AtomicReference<>();
        TriageProvider failingGemini = new TriageProvider() {
            @Override
            public ProviderCall analyze(TriageRequest ignoredRequest, RedactedTicket ignoredTicket) {
                throw new ProviderCallException("Gemini provider request failed", "HTTP_503");
            }

            @Override public String providerName() { return "gemini"; }
            @Override public String modelVersion() { return "gemini-3.5-flash"; }
            @Override public String promptVersion() { return "triage-v1"; }
        };
        TriageEngine engine = new TriageEngine(new TicketRedactor(), failingGemini, new RulesFallbackProvider(),
                new SemanticValidator(PolicyCatalog.defaultCatalog()), new PriorityScorer(), invocation::set,
                Clock.systemUTC());

        engine.triage(request);

        assertThat(invocation.get().provider()).isEqualTo("gemini");
        assertThat(invocation.get().modelVersion()).isEqualTo("gemini-3.5-flash");
        assertThat(invocation.get().sanitizedErrorCode()).isEqualTo("HTTP_503");
    }
}
