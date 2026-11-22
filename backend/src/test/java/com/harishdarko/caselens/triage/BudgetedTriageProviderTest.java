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
import java.util.Map;
import java.util.Optional;
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

    /** CL2-04: one client used to be able to spend the whole day's budget, leaving everyone else on rules. */
    @Test
    void aClientPastItsDailyShareGetsRulesWhileOtherClientsStillGetAi() {
        AtomicInteger providerCalls = new AtomicInteger();
        TriageRequest fromA = request();
        TriageRequest alsoFromA = request();
        TriageRequest fromB = request();
        Map<UUID, String> clientOfTicket = Map.of(fromA.ticketId(), "client-a", alsoFromA.ticketId(), "client-a",
                fromB.ticketId(), "client-b");
        AiCallBudget budget = new AiCallBudget(new RateLimitService(clock, 30, Duration.ofMinutes(1)), clock, 10, 2);
        AtomicReference<ModelInvocationRecord> invocation = new AtomicReference<>();
        TriageEngine engine = engine(new BudgetedTriageProvider(counting(providerCalls), budget,
                ticketId -> Optional.ofNullable(clientOfTicket.get(ticketId))), invocation);

        assertThat(engine.triage(fromA).decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
        assertThat(engine.triage(alsoFromA).decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
        assertThat(engine.triage(fromA).decisionSource()).isEqualTo(DecisionSource.RULES_FALLBACK);
        assertThat(invocation.get().sanitizedErrorCode()).isEqualTo(BudgetedTriageProvider.SHARE_EXHAUSTED);

        assertThat(engine.triage(fromB).decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
        assertThat(engine.triage(fromB).decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
        assertThat(providerCalls).hasValue(4);

        // A new UTC day gives every client its share again.
        clock.advance(Duration.ofHours(1));
        assertThat(engine.triage(alsoFromA).decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
    }

    @Test
    void aClientPastItsShareDoesNotSpendTheGlobalBudget() {
        AtomicInteger providerCalls = new AtomicInteger();
        TriageRequest fromA = request();
        TriageRequest fromB = request();
        Map<UUID, String> clientOfTicket = Map.of(fromA.ticketId(), "client-a", fromB.ticketId(), "client-b");
        AiCallBudget budget = new AiCallBudget(new RateLimitService(clock, 30, Duration.ofMinutes(1)), clock, 2, 1);
        AtomicReference<ModelInvocationRecord> invocation = new AtomicReference<>();
        TriageEngine engine = engine(new BudgetedTriageProvider(counting(providerCalls), budget,
                ticketId -> Optional.ofNullable(clientOfTicket.get(ticketId))), invocation);

        engine.triage(fromA);
        for (int retry = 0; retry < 5; retry++) engine.triage(fromA);
        assertThat(engine.triage(fromB).decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
        assertThat(providerCalls).hasValue(2);

        // With both shares spent, the global budget (2) is also spent, and the error code says which ran out.
        TriageRequest fromC = request();
        TriageEngine third = engine(new BudgetedTriageProvider(counting(providerCalls), budget,
                ticketId -> Optional.of("client-c")), invocation);
        assertThat(third.triage(fromC).decisionSource()).isEqualTo(DecisionSource.RULES_FALLBACK);
        assertThat(invocation.get().sanitizedErrorCode()).isEqualTo(BudgetedTriageProvider.BUDGET_EXHAUSTED);
    }

    @Test
    void aWorkspaceWithoutAClientKeyOrAFailedLookupCountsAgainstTheGlobalBudgetOnly() {
        AtomicInteger providerCalls = new AtomicInteger();
        AiCallBudget budget = new AiCallBudget(new RateLimitService(clock, 30, Duration.ofMinutes(1)), clock, 3, 1);
        TriageEngine noKey = engine(new BudgetedTriageProvider(counting(providerCalls), budget,
                ticketId -> Optional.empty()), new AtomicReference<>());
        TriageEngine lookupFails = engine(new BudgetedTriageProvider(counting(providerCalls), budget,
                ticketId -> { throw new IllegalStateException("database unavailable"); }), new AtomicReference<>());

        assertThat(noKey.triage(request).decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
        assertThat(noKey.triage(request).decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
        assertThat(lookupFails.triage(request).decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
        assertThat(noKey.triage(request).decisionSource()).isEqualTo(DecisionSource.RULES_FALLBACK);
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

    private static TriageRequest request() {
        return new TriageRequest(UUID.randomUUID(), "Charger failed",
                "The charger connector failed and charging never started for this session.", TicketChannel.WEB, 0);
    }

    private static TriageProvider counting(AtomicInteger providerCalls) {
        return new TriageProvider() {
            private final MockTriageProvider delegate = new MockTriageProvider();

            @Override
            public ProviderCall analyze(TriageRequest triageRequest, RedactedTicket redactedTicket) {
                providerCalls.incrementAndGet();
                return delegate.analyze(triageRequest, redactedTicket);
            }

            @Override public String providerName() { return "groq"; }
        };
    }

    private TriageEngine engine(TriageProvider provider, AtomicReference<ModelInvocationRecord> invocation) {
        return new TriageEngine(new TicketRedactor(), provider, new RulesFallbackProvider(),
                new SemanticValidator(PolicyCatalog.defaultCatalog()), new PriorityScorer(), invocation::set, clock);
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
