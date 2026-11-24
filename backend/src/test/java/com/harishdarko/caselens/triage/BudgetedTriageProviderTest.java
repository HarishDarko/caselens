package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harishdarko.caselens.security.AiCallBudget;
import com.harishdarko.caselens.security.RateLimitService;
import com.harishdarko.caselens.ticket.TicketChannel;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
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
import tools.jackson.databind.ObjectMapper;

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

    /**
     * One budget unit per delivery that actually calls the provider: Groq's in-call retry after a 429 shares delivery
     * 1's unit, and an SQS redelivery charges a second unit. Because rate-limited retries stop at 2 deliveries, one
     * job spends at most 2 units.
     */
    @Test
    void aRateLimitedJobSpendsOneUnitPerDeliveryAndSharesItAcrossInCallRetry() throws Exception {
        AtomicInteger httpRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/groq", exchange -> {
            int call = httpRequests.incrementAndGet();
            byte[] body = (call <= 2 ? "rate limited" : groqAnswer()).getBytes(StandardCharsets.UTF_8);
            if (call <= 2) exchange.getResponseHeaders().add("retry-after", "1");
            exchange.sendResponseHeaders(call <= 2 ? 429 : 200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            GroqTriageProvider groq = new GroqTriageProvider(URI.create("http://localhost:" + server.getAddress().getPort() + "/groq"),
                    "key", "llama-3.1-8b-instant", HttpClient.newHttpClient(), new ObjectMapper(), Duration.ofSeconds(20),
                    () -> 0, GroqTriageProvider.CALL_BUDGET, () -> 0L, millis -> { });
            AiCallBudget budget = new AiCallBudget(new RateLimitService(clock, 30, Duration.ofMinutes(1)), clock, 2);
            TriageEngine engine = new TriageEngine(new TicketRedactor(), new BudgetedTriageProvider(groq, budget),
                    new RulesFallbackProvider(), new SemanticValidator(PolicyCatalog.defaultCatalog()), new PriorityScorer(),
                    ignored -> { }, clock);
            UUID eventId = UUID.randomUUID();

            assertThatThrownBy(() -> engine.triage(request, eventId, false)).isInstanceOf(ProviderCallException.class);
            assertThat(httpRequests).as("delivery 1 call and its in-call retry share 1 budget unit").hasValue(2);
            assertThat(engine.triage(request, eventId, true).decisionSource())
                    .as("delivery 2 spends the 2nd budget unit and succeeds").isEqualTo(DecisionSource.AI_VALIDATED);
            assertThat(httpRequests).hasValue(3);

            TriageDecision spent = engine.triage(request, UUID.randomUUID(), true);
            assertThat(spent.fallbackReason()).as("2 deliveries spent the 2-unit budget").isEqualTo(FallbackReason.BUDGET_SPENT);
            assertThat(httpRequests).hasValue(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aDeniedClientShareOrGlobalBudgetNeverTurnsIntoAGrantOnRedelivery() {
        AtomicInteger providerCalls = new AtomicInteger();
        TriageRequest first = request();
        TriageRequest second = request();
        Map<UUID, String> clientOfTicket = Map.of(first.ticketId(), "client-a", second.ticketId(), "client-a");
        AiCallBudget budget = new AiCallBudget(new RateLimitService(clock, 30, Duration.ofMinutes(1)), clock, 10, 1);
        AtomicReference<ModelInvocationRecord> invocation = new AtomicReference<>();
        TriageEngine engine = engine(new BudgetedTriageProvider(counting(providerCalls), budget,
                ticketId -> Optional.ofNullable(clientOfTicket.get(ticketId))), invocation);

        assertThat(engine.triage(first, UUID.randomUUID(), true).decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
        assertThat(providerCalls).hasValue(1);

        UUID redeliveredEvent = UUID.randomUUID();
        assertThat(engine.triage(second, redeliveredEvent, false).fallbackReason()).isEqualTo(FallbackReason.BUDGET_SPENT);
        assertThat(invocation.get().sanitizedErrorCode()).isEqualTo(BudgetedTriageProvider.SHARE_EXHAUSTED);
        // Redelivery of the exact same eventId must still be denied and never call the provider.
        assertThat(engine.triage(second, redeliveredEvent, true).fallbackReason()).isEqualTo(FallbackReason.BUDGET_SPENT);
        assertThat(invocation.get().sanitizedErrorCode()).isEqualTo(BudgetedTriageProvider.SHARE_EXHAUSTED);
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void aDatabaseFailureDuringBudgetCheckNeverLeadsToAProviderCallOnSameOrLaterDelivery() {
        AtomicInteger providerCalls = new AtomicInteger();
        // Every database call fails, as during a Neon outage: the budget check cannot answer.
        org.springframework.jdbc.core.JdbcTemplate jdbc = org.mockito.Mockito.mock(
                org.springframework.jdbc.core.JdbcTemplate.class, invocation -> {
                    throw new org.springframework.dao.DataAccessResourceFailureException("neon transient error");
                });
        AiCallBudget budget = new AiCallBudget(new RateLimitService(clock, 30, Duration.ofMinutes(1), jdbc), clock, 10, 5);
        TriageEngine engine = engine(new BudgetedTriageProvider(counting(providerCalls), budget,
                ticketId -> Optional.of("client-a")), new AtomicReference<>());
        UUID eventId = UUID.randomUUID();

        assertThat(engine.triage(request, eventId, false).decisionSource()).isEqualTo(DecisionSource.RULES_FALLBACK);
        assertThat(engine.triage(request, eventId, true).decisionSource()).isEqualTo(DecisionSource.RULES_FALLBACK);
        assertThat(providerCalls).as("failed budget checks must never call the provider").hasValue(0);
    }

    @Test
    void aFullInMemoryBudgetMapDeniesInsteadOfGranting() {
        AtomicInteger providerCalls = new AtomicInteger();
        RateLimitService limits = new RateLimitService(clock, 30, Duration.ofMinutes(1));
        for (int key = 0; key < 10_000; key++) {
            limits.acquireBudget("ai-share:2040-07-11:other-" + key, 1, Duration.ofHours(12));
        }
        AiCallBudget budget = new AiCallBudget(limits, clock, 10, 5);
        TriageEngine engine = engine(new BudgetedTriageProvider(counting(providerCalls), budget,
                ticketId -> Optional.of("new-client")), new AtomicReference<>());

        TriageDecision decision = engine.triage(request, UUID.randomUUID(), true);

        assertThat(decision.decisionSource()).isEqualTo(DecisionSource.RULES_FALLBACK);
        assertThat(decision.fallbackReason()).isEqualTo(FallbackReason.BUDGET_SPENT);
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void aTinyTimeBudgetSpendsNoDailyBudgetAndMakesNoProviderCall() {
        AtomicInteger providerCalls = new AtomicInteger();
        AiCallBudget budget = new AiCallBudget(new RateLimitService(clock, 30, Duration.ofMinutes(1)), clock, 1, 1);
        BudgetedTriageProvider provider = new BudgetedTriageProvider(counting(providerCalls), budget,
                ticketId -> Optional.of("client-a"));

        for (Duration tiny : java.util.List.of(Duration.ZERO, Duration.ofMillis(2_999))) {
            assertThatThrownBy(() -> provider.analyze(request.withTimeBudget(tiny),
                    new RedactedTicket(request.subject(), request.message())))
                    .isInstanceOf(ProviderCallException.class)
                    .extracting(exception -> ((ProviderCallException) exception).errorCode())
                    .isEqualTo("TIME_BUDGET_EXHAUSTED");
        }
        assertThat(providerCalls).hasValue(0);
        // The single unit of daily budget must still be intact for a real call.
        assertThat(provider.analyze(request, new RedactedTicket(request.subject(), request.message()))).isNotNull();
        assertThat(providerCalls).hasValue(1);
    }

    private static String groqAnswer() {
        String content = "{\"category\":\"CHARGER_HARDWARE\",\"urgency\":\"HIGH\",\"slaRisk\":\"HIGH\",\"sentiment\":\"NEGATIVE\","
                + "\"summary\":\"The charger connector failed for this session.\","
                + "\"evidence\":[{\"quote\":\"The charger connector failed\",\"meaning\":\"Hardware fault.\"}],"
                + "\"policyIds\":[],\"explanation\":\"The connector failed and charging never started, so this needs review.\","
                + "\"recommendedActions\":[\"Inspect the connector\"],"
                + "\"suggestedReply\":\"We are checking the charger connector now.\",\"warnings\":[]}";
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.putArray("choices").addObject().putObject("message").put("content", content);
        return root.toString();
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
