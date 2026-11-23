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
                new ProviderTriageResult(Category.of("CHARGER_HARDWARE"), Urgency.HIGH, SlaRisk.HIGH, Sentiment.NEGATIVE,
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
        assertThat(decision.fallbackReason()).isEqualTo(FallbackReason.ANSWER_FAILED_CHECKS);
        assertThat(decision.fallback().errorCode()).isEqualTo("SEMANTIC_VALIDATION");
        assertThat(decision.fallback().failedCheck()).isEqualTo("QUOTE_NOT_FOUND");
        assertThat(decision.fallback().pack()).isEqualTo("ev-charging");
    }

    @Test
    void anAiValidatedDecisionHasNoFallbackReason() {
        TriageDecision decision = new TriageEngine(new MockTriageProvider(), new RulesFallbackProvider(),
                new SemanticValidator(PolicyCatalog.defaultCatalog()), new PriorityScorer()).triage(request);

        assertThat(decision.decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
        assertThat(decision.fallback()).isNull();
        assertThat(decision.fallbackReason()).isNull();
    }

    @Test
    void namesTheProviderErrorAndItsPlainReasonOnAFallback() {
        assertThat(fallbackFor(new ProviderCallException("Groq provider request failed", "HTTP_429")))
                .isEqualTo(new FallbackCause(FallbackReason.PROVIDER_RATE_LIMITED, "HTTP_429", null, "ev-charging", "groq"));
        assertThat(fallbackFor(new ProviderCallException("The daily AI call budget is spent", "AI_BUDGET_EXHAUSTED")).reason())
                .isEqualTo(FallbackReason.BUDGET_SPENT);
        assertThat(fallbackFor(new ProviderCallException("Groq provider returned invalid structured output", "SCHEMA_FAILURE")).reason())
                .isEqualTo(FallbackReason.ANSWER_UNREADABLE);
        assertThat(fallbackFor(new IllegalStateException("bug")))
                .isEqualTo(new FallbackCause(FallbackReason.PROVIDER_UNAVAILABLE, "PROVIDER_FAILURE", null, "ev-charging", "groq"));
    }

    @Test
    void keepsAnUnexpectedErrorCodeOutOfTheFallbackCause() {
        FallbackCause cause = fallbackFor(new ProviderCallException("odd", "secret value from the ticket"));

        assertThat(cause.errorCode()).isEqualTo("UNRECOGNIZED");
        assertThat(cause.reason()).isEqualTo(FallbackReason.PROVIDER_UNAVAILABLE);
    }

    private FallbackCause fallbackFor(RuntimeException failure) {
        TriageProvider failing = new TriageProvider() {
            @Override
            public ProviderCall analyze(TriageRequest ignoredRequest, RedactedTicket ignoredTicket) {
                throw failure;
            }

            @Override public String providerName() { return "groq"; }
            @Override public String modelVersion() { return "openai/gpt-oss-20b"; }
            @Override public String promptVersion() { return "triage-v2"; }
        };
        TriageDecision decision = new TriageEngine(failing, new RulesFallbackProvider(),
                new SemanticValidator(PolicyCatalog.defaultCatalog()), new PriorityScorer()).triage(request);
        assertThat(decision.decisionSource()).isEqualTo(DecisionSource.RULES_FALLBACK);
        assertThat(decision.warnings()).as("old clients keep the warning text").contains("AI provider result unavailable or invalid");
        assertThat(decision.fallbackReason()).isEqualTo(decision.fallback().reason());
        return decision.fallback();
    }

    @Test
    void recordsTheJobsEventIdOnEachInvocation() {
        AtomicReference<ModelInvocationRecord> invocation = new AtomicReference<>();
        TriageEngine engine = new TriageEngine(new TicketRedactor(), new MockTriageProvider(), new RulesFallbackProvider(),
                new SemanticValidator(PolicyCatalog.defaultCatalog()), new PriorityScorer(), invocation::set, Clock.systemUTC());
        UUID eventId = UUID.randomUUID();

        engine.triage(request, eventId);

        assertThat(invocation.get().eventId()).isEqualTo(eventId);
        assertThat(invocation.get().ticketId()).isEqualTo(request.ticketId());
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
