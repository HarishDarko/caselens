package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.harishdarko.caselens.security.AiCallBudget;
import com.harishdarko.caselens.security.RateLimitService;
import com.harishdarko.caselens.ticket.TicketChannel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BudgetedTriageProviderTest {
    private final TriageRequest request = new TriageRequest(UUID.randomUUID(), "Charger failed",
            "The charger connector failed and charging never started for this session.", TicketChannel.WEB, 0);
    private final MutableClock clock = new MutableClock(Instant.parse("2040-07-11T23:00:00Z"));

    @Test
    void fallsBackToRulesOnceTheDailyBudgetIsSpentAndResetsAtUtcMidnight() {
        AtomicInteger providerCalls = new AtomicInteger();
        TriageProvider counting = new TriageProvider() {
            private final MockTriageProvider delegate = new MockTriageProvider();

            @Override
            public ProviderCall analyze(TriageRequest triageRequest, RedactedTicket redactedTicket) {
                providerCalls.incrementAndGet();
                return delegate.analyze(triageRequest, redactedTicket);
            }

            @Override public String providerName() { return "groq"; }
        };
        AiCallBudget budget = new AiCallBudget(new RateLimitService(clock, 30, Duration.ofMinutes(1)), clock, 2);
        AtomicReference<ModelInvocationRecord> invocation = new AtomicReference<>();
        TriageEngine engine = new TriageEngine(new TicketRedactor(), new BudgetedTriageProvider(counting, budget),
                new RulesFallbackProvider(), new SemanticValidator(PolicyCatalog.defaultCatalog()), new PriorityScorer(),
                invocation::set, clock);

        assertThat(engine.triage(request).decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
        assertThat(engine.triage(request).decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
        TriageDecision overBudget = engine.triage(request);

        assertThat(overBudget.decisionSource()).isEqualTo(DecisionSource.RULES_FALLBACK);
        assertThat(providerCalls).hasValue(2);
        assertThat(invocation.get().provider()).isEqualTo("groq");
        assertThat(invocation.get().sanitizedErrorCode()).isEqualTo(BudgetedTriageProvider.BUDGET_EXHAUSTED);

        clock.advance(Duration.ofHours(1));
        assertThat(engine.triage(request).decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
        assertThat(providerCalls).hasValue(3);
    }

    @Test
    void aZeroBudgetNeverCallsTheProvider() {
        AtomicInteger providerCalls = new AtomicInteger();
        TriageProvider counting = (triageRequest, redactedTicket) -> {
            providerCalls.incrementAndGet();
            return new MockTriageProvider().analyze(triageRequest, redactedTicket);
        };
        AiCallBudget budget = new AiCallBudget(new RateLimitService(clock, 30, Duration.ofMinutes(1)), clock, 0);
        TriageEngine engine = new TriageEngine(new TicketRedactor(), new BudgetedTriageProvider(counting, budget),
                new RulesFallbackProvider(), new SemanticValidator(PolicyCatalog.defaultCatalog()), new PriorityScorer(),
                ignored -> {}, clock);

        assertThat(engine.triage(request).decisionSource()).isEqualTo(DecisionSource.RULES_FALLBACK);
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void onlyRealProvidersSpendTheBudget() {
        AiCallBudget budget = new AiCallBudget(new RateLimitService(clock, 30, Duration.ofMinutes(1)), clock, 1);
        TriageProvider groq = (triageRequest, redactedTicket) -> null;

        assertThat(TriageConfiguration.budgeted(new MockTriageProvider(), budget)).isInstanceOf(MockTriageProvider.class);
        assertThat(TriageConfiguration.budgeted(groq, budget)).isInstanceOf(BudgetedTriageProvider.class);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
