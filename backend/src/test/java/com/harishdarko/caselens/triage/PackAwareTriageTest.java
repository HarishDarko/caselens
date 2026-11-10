package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harishdarko.caselens.ticket.TicketChannel;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Triage with a non-EV pack: its categories, policies and vocabulary are used, and another pack's are rejected. */
class PackAwareTriageTest {
    private static final String SUBJECT = "Locked out of my account";
    private static final String MESSAGE = "I am locked out after the password reset. The login page keeps failing.";

    private final PackRules saas = TestPacks.saas();
    private final TriageRequest request = new TriageRequest(UUID.randomUUID(), SUBJECT, MESSAGE, TicketChannel.WEB, 0, saas);

    @Test
    void theRulesUseTheWorkspacePack() {
        ProviderTriageResult result = new RulesFallbackProvider().analyze(request, new RedactedTicket(SUBJECT, MESSAGE)).result();

        assertThat(result.category()).isEqualTo(Category.of("LOGIN"));
        assertThat(result.urgency()).isEqualTo(Urgency.HIGH);
        assertThat(result.policyIds()).containsExactly("ACCOUNT_LOCKOUT");
        assertThat(result.recommendedActions()).containsExactly("Verify the user's identity", "Clear the account lockout");
    }

    @Test
    void anUnmatchedTicketGetsThePacksGeneralCategory() {
        TriageRequest general = new TriageRequest(UUID.randomUUID(), "Small note here", "Nothing in this message matches a rule at all.",
                TicketChannel.WEB, 0, saas);

        ProviderTriageResult result = new RulesFallbackProvider().analyze(general,
                new RedactedTicket(general.subject(), general.message())).result();

        assertThat(result.category()).isEqualTo(Category.of("GENERAL"));
        assertThat(result.urgency()).isEqualTo(Urgency.LOW);
    }

    @Test
    void theResponseSchemaAllowsOnlyThePacksCategoriesAndPolicies() throws Exception {
        TriageResponseSchema schema = new TriageResponseSchema(new ObjectMapper(), saas);
        JsonNode properties = schema.schema().path("properties");

        assertThat(strings(properties.path("category").path("enum"))).containsExactly("LOGIN", "INVOICE", "GENERAL");
        assertThat(strings(properties.path("policyIds").path("items").path("enum"))).containsExactly("ACCOUNT_LOCKOUT", "INVOICE_DISPUTE");
        assertThatThrownBy(() -> schema.parse(answer("BILLING", List.of()), "model", "triage-v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(schema.parse(answer("LOGIN", List.of("ACCOUNT_LOCKOUT")), "model", "triage-v1").category())
                .isEqualTo(Category.of("LOGIN"));
    }

    @Test
    void aPolicyFromAnotherPackIsRejectedAndTriageFallsBackToThePackRules() {
        TriageDecision decision = engine(provider("LOGIN", List.of("PAYMENT_CAPTURE_NO_SESSION"))).triage(request);

        assertThat(decision.decisionSource()).isEqualTo(DecisionSource.RULES_FALLBACK);
        assertThat(decision.category()).isEqualTo(Category.of("LOGIN"));
        assertThat(decision.policyIds()).containsExactly("ACCOUNT_LOCKOUT");
    }

    @Test
    void aCategoryFromAnotherPackIsRejectedAndTriageFallsBackToThePackRules() {
        assertThatThrownBy(() -> new SemanticValidator(PolicyCatalog.defaultCatalog())
                .validate(provider("BILLING", List.of()).analyze(request, null).result(), new RedactedTicket(SUBJECT, MESSAGE), saas))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessage("Provider returned a category outside the industry pack");

        TriageDecision decision = engine(provider("BILLING", List.of())).triage(request);

        assertThat(decision.decisionSource()).isEqualTo(DecisionSource.RULES_FALLBACK);
        assertThat(decision.category()).isEqualTo(Category.of("LOGIN"));
    }

    @Test
    void aValidAnswerInThePackIsAccepted() {
        TriageDecision decision = engine(provider("LOGIN", List.of("ACCOUNT_LOCKOUT"))).triage(request);

        assertThat(decision.decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
        assertThat(decision.category()).isEqualTo(Category.of("LOGIN"));
        assertThat(decision.policyIds()).containsExactly("ACCOUNT_LOCKOUT");
    }

    @Test
    void theScorerUsesThePackIndicators() {
        PriorityResult result = new PriorityScorer().score(new PriorityContext(Urgency.HIGH, SlaRisk.MEDIUM, Category.of("LOGIN"),
                SUBJECT + "\n" + MESSAGE, 0, saas));

        assertThat(result.appliedRules()).extracting(RuleApplication::code)
                .containsExactly("URGENCY_HIGH", "SLA_RISK_MEDIUM", "SERVICE_BLOCKED");
        assertThat(result.appliedRules().get(2).explanation()).isEqualTo("Sign-in is blocked");
    }

    @Test
    void theGroqRequestListsOnlyThePacksPolicies() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = new ObjectMapper().createObjectNode().set("choices", new ObjectMapper().createArrayNode()
                    .add(new ObjectMapper().createObjectNode().set("message", new ObjectMapper().createObjectNode()
                            .put("content", answer("LOGIN", List.of("ACCOUNT_LOCKOUT")))))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            ProviderCall call = new GroqTriageProvider(URI.create("http://localhost:" + server.getAddress().getPort() + "/chat"),
                    "test-key", "openai/gpt-oss-20b", HttpClient.newHttpClient(), new ObjectMapper(), Duration.ofSeconds(2), () -> 0)
                    .analyze(request, new RedactedTicket(SUBJECT, MESSAGE));

            assertThat(call.result().category()).isEqualTo(Category.of("LOGIN"));
            assertThat(body.get()).contains("Allowed policyIds: ACCOUNT_LOCKOUT, INVOICE_DISPUTE.")
                    .contains("\"enum\":[\"LOGIN\",\"INVOICE\",\"GENERAL\"]")
                    .doesNotContain("PAYMENT_CAPTURE_NO_SESSION").doesNotContain("CHARGING_SESSION");
        } finally {
            server.stop(0);
        }
    }

    private TriageEngine engine(TriageProvider primary) {
        return new TriageEngine(new TicketRedactor(), primary, new RulesFallbackProvider(),
                new SemanticValidator(PolicyCatalog.defaultCatalog()), new PriorityScorer(), ignored -> {}, Clock.systemUTC());
    }

    private TriageProvider provider(String category, List<String> policyIds) {
        return (ignoredRequest, ignoredTicket) -> new ProviderCall(new ProviderTriageResult(Category.of(category), Urgency.HIGH,
                SlaRisk.MEDIUM, Sentiment.NEGATIVE, "The user is locked out after a password reset.",
                List.of(new EvidenceItem(SUBJECT, "The subject names the problem."),
                        new EvidenceItem("I am locked out after the password reset.", "The message confirms it.")),
                policyIds, "The account is locked after a reset, so sign-in is blocked for this user.",
                List.of("Verify the user's identity"), "We are checking your account and will update you shortly.",
                List.of(), "fake-v1", "triage-v1"), null, null);
    }

    private String answer(String category, List<String> policyIds) {
        ObjectMapper mapper = new ObjectMapper();
        var node = mapper.createObjectNode().put("category", category).put("urgency", "HIGH").put("slaRisk", "MEDIUM")
                .put("sentiment", "NEGATIVE").put("summary", "The user is locked out after a password reset.")
                .put("explanation", "The account is locked after a reset, so sign-in is blocked for this user.")
                .put("suggestedReply", "We are checking your account and will update you shortly.");
        node.putArray("evidence").addObject().put("quote", SUBJECT).put("meaning", "The subject names the problem.");
        policyIds.forEach(node.putArray("policyIds")::add);
        node.putArray("recommendedActions").add("Verify the user's identity");
        node.putArray("warnings");
        return node.toString();
    }

    private List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asText).toList();
    }
}
