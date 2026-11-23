package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harishdarko.caselens.ticket.TicketChannel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GroqTriageProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsOpenAiCompatibleJsonRequestAndRetriesOneTransientResponse() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            int call = calls.incrementAndGet();
            if (call == 1) respond(exchange, 429, "busy");
            else respond(exchange, 200, validResponse());
        });
        server.start();

        ProviderCall call = provider().analyze(request(), new RedactedTicket(
                "Payment accepted", "Payment accepted but session never started."));

        assertThat(calls).hasValue(2);
        assertThat(call.result().category()).isEqualTo(Category.of("BILLING"));
        assertThat(call.inputTokens()).isEqualTo(12L);
        assertThat(call.outputTokens()).isEqualTo(8L);
        assertThat(body.get()).contains("\"model\":\"llama-3.1-8b-instant\"");
        assertThat(body.get()).contains("\"response_format\":{\"type\":\"json_object\"}");
        assertThat(body.get()).contains("\"role\":\"system\"");
        assertThat(body.get()).contains("PAYMENT_CAPTURE_NO_SESSION");
        assertThat(authorization.get()).isEqualTo("Bearer secret-key");
        assertThat(body.get()).doesNotContain("secret-key");
    }

    @Test
    void usesStrictJsonSchemaForGroqModelsThatSupportIt() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes()));
            respond(exchange, 200, validResponse());
        });
        server.start();

        ProviderCall call = new GroqTriageProvider(URI.create("http://localhost:" + server.getAddress().getPort()
                        + "/openai/v1/chat/completions"), "secret-key", "openai/gpt-oss-20b",
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), new ObjectMapper(),
                Duration.ofSeconds(2), () -> 0).analyze(request(), new RedactedTicket(
                        "Payment accepted", "Payment accepted but session never started."));

        assertThat(call.result().modelVersion()).isEqualTo("openai/gpt-oss-20b");
        assertThat(body.get()).contains("\"type\":\"json_schema\"");
        assertThat(body.get()).contains("\"strict\":true");
        assertThat(body.get()).contains("\"additionalProperties\":false");
    }

    @Test
    void doesNotRetryAuthenticationFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 401, "unauthorized");
        });
        server.start();

        assertThatThrownBy(() -> provider().analyze(request(), new RedactedTicket(
                "Payment accepted", "Payment accepted but session never started.")))
                .isInstanceOf(ProviderCallException.class)
                .hasMessage("Groq provider request was rejected")
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("AUTHENTICATION_FAILURE");
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsAnOversizedProviderResponseBeforeParsing() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> respond(exchange, 200, "x".repeat(65_537)));
        server.start();

        assertThatThrownBy(() -> provider().analyze(request(), new RedactedTicket(
                "Payment accepted", "Payment accepted but session never started.")))
                .isInstanceOf(ProviderCallException.class)
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("RESPONSE_TOO_LARGE");
    }

    @Test
    void waitsForRetryAfterOnARateLimitAndRetriesInsideTheCallBudget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("retry-after", "3");
                exchange.getResponseHeaders().add("x-ratelimit-reset-tokens", "9.5s");
                respond(exchange, 429, "rate limited");
            } else {
                respond(exchange, 200, validResponse());
            }
        });
        server.start();
        List<Long> sleeps = new ArrayList<>();

        ProviderCall call = timedProvider(() -> 0L, sleeps::add).analyze(request(), ticket());

        assertThat(calls).hasValue(2);
        assertThat(sleeps).as("retry-after wins over the reset headers").containsExactly(3_000L);
        assertThat(call.result().category()).isEqualTo(Category.of("BILLING"));
    }

    @Test
    void usesTheTokenResetHeaderWhenARateLimitHasNoRetryAfter() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("x-ratelimit-reset-tokens", "7.66s");
                exchange.getResponseHeaders().add("x-ratelimit-reset-requests", "2m59.56s");
                respond(exchange, 429, "rate limited");
            } else {
                respond(exchange, 200, validResponse());
            }
        });
        server.start();
        List<Long> sleeps = new ArrayList<>();

        timedProvider(() -> 0L, sleeps::add).analyze(request(), ticket());

        assertThat(sleeps).containsExactly(7_660L);
    }

    @Test
    void endsTheAttemptAsRateLimitedWithoutWaitingWhenTheWaitDoesNotFitTheCallBudget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("retry-after", "39");
            respond(exchange, 429, "rate limited");
        });
        server.start();
        List<Long> sleeps = new ArrayList<>();

        assertThatThrownBy(() -> timedProvider(() -> 0L, sleeps::add).analyze(request(), ticket()))
                .isInstanceOf(ProviderCallException.class)
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("HTTP_429");
        assertThat(calls).as("no second request when the wait cannot fit").hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void countsTheTimeAlreadySpentAgainstTheWait() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("retry-after", "5");
            respond(exchange, 429, "rate limited");
        });
        server.start();
        AtomicLong now = new AtomicLong();
        LongSupplier clock = () -> now.getAndAdd(Duration.ofSeconds(34).toNanos()); // each reading is 34 s later
        List<Long> sleeps = new ArrayList<>();

        assertThatThrownBy(() -> timedProvider(clock, sleeps::add).analyze(request(), ticket()))
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("HTTP_429");
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aSecondRateLimitAfterTheWaitEndsTheAttemptAsRateLimited() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("retry-after", "1");
            respond(exchange, 429, "rate limited");
        });
        server.start();

        assertThatThrownBy(() -> timedProvider(() -> 0L, millis -> { }).analyze(request(), ticket()))
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("HTTP_429");
        assertThat(calls).hasValue(2);
    }

    @Test
    void readsAStrictSchemaRejectionAsAnUnreadableAnswerWithoutRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 400, "{\"error\":{\"message\":\"Generated JSON does not match the expected schema\","
                    + "\"type\":\"invalid_request_error\",\"code\":\"json_validate_failed\","
                    + "\"failed_generation\":\"{\\\"summary\\\":\\\"ticket text\\\"}\"}}");
        });
        server.start();

        assertThatThrownBy(() -> provider().analyze(request(), ticket()))
                .isInstanceOf(ProviderCallException.class)
                .hasMessageNotContaining("ticket text")
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("SCHEMA_FAILURE");
        assertThat(calls).hasValue(1);
    }

    @Test
    void keepsOtherBadRequestsAndRequestTooLargeAsHttpCodesWithoutRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger status = new AtomicInteger(400);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            respond(exchange, status.get(), "{\"error\":{\"code\":\"model_not_found\"}}");
        });
        server.start();

        assertThatThrownBy(() -> provider().analyze(request(), ticket()))
                .extracting(exception -> ((ProviderCallException) exception).errorCode()).isEqualTo("HTTP_400");
        status.set(413);
        assertThatThrownBy(() -> provider().analyze(request(), ticket()))
                .extracting(exception -> ((ProviderCallException) exception).errorCode()).isEqualTo("HTTP_413");
        assertThat(calls).hasValue(2);
    }

    @Test
    void asksGptOssForLowReasoningAndEveryModelForABoundedAnswer() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes()));
            respond(exchange, 200, validResponse());
        });
        server.start();
        ObjectMapper mapper = new ObjectMapper();

        provider("openai/gpt-oss-20b").analyze(request(), ticket());
        JsonNode gptOss = mapper.readTree(body.get());
        provider("llama-3.1-8b-instant").analyze(request(), ticket());
        JsonNode llama = mapper.readTree(body.get());

        assertThat(gptOss.path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(gptOss.path("max_completion_tokens").asInt()).isEqualTo(GroqTriageProvider.MAX_COMPLETION_TOKENS);
        assertThat(llama.has("reasoning_effort")).as("only reasoning models take reasoning_effort").isFalse();
        assertThat(llama.path("max_completion_tokens").asInt()).isEqualTo(GroqTriageProvider.MAX_COMPLETION_TOKENS);
    }

    @Test
    void parsesGroqResetDurations() {
        assertThat(GroqTriageProvider.parseDuration("7.66s")).contains(Duration.ofMillis(7_660));
        assertThat(GroqTriageProvider.parseDuration("2m59.56s")).contains(Duration.ofMillis(179_560));
        assertThat(GroqTriageProvider.parseDuration("1h2m3s")).contains(Duration.ofSeconds(3_723));
        assertThat(GroqTriageProvider.parseDuration("250ms")).contains(Duration.ofMillis(250));
        assertThat(GroqTriageProvider.parseDuration("3")).contains(Duration.ofSeconds(3));
        assertThat(GroqTriageProvider.parseDuration("1.5")).contains(Duration.ofMillis(1_500));
        assertThat(GroqTriageProvider.parseDuration("soon")).isEmpty();
        assertThat(GroqTriageProvider.parseDuration("")).isEmpty();
        assertThat(GroqTriageProvider.parseDuration(null)).isEmpty();
    }

    private GroqTriageProvider timedProvider(LongSupplier nanoTime, GroqTriageProvider.Sleeper sleeper) {
        return new GroqTriageProvider(URI.create("http://localhost:" + server.getAddress().getPort()
                        + "/openai/v1/chat/completions"), "secret-key", "llama-3.1-8b-instant",
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), new ObjectMapper(),
                Duration.ofSeconds(2), () -> 0, GroqTriageProvider.CALL_BUDGET, nanoTime, sleeper);
    }

    private GroqTriageProvider provider(String model) {
        return new GroqTriageProvider(URI.create("http://localhost:" + server.getAddress().getPort()
                        + "/openai/v1/chat/completions"), "secret-key", model,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), new ObjectMapper(),
                Duration.ofSeconds(2), () -> 0);
    }

    private RedactedTicket ticket() {
        return new RedactedTicket("Payment accepted", "Payment accepted but session never started.");
    }

    private GroqTriageProvider provider() {
        return new GroqTriageProvider(URI.create("http://localhost:" + server.getAddress().getPort()
                        + "/openai/v1/chat/completions"), "secret-key", "llama-3.1-8b-instant",
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), new ObjectMapper(),
                Duration.ofSeconds(2), () -> 0);
    }

    private TriageRequest request() {
        return new TriageRequest(UUID.randomUUID(), "Payment accepted", "Payment accepted but session never started.",
                TicketChannel.WEB, 0);
    }

    private String validResponse() {
        return """
                {"choices":[{"message":{"role":"assistant","content":"{\\"category\\":\\"BILLING\\",\\"urgency\\":\\"HIGH\\",\\"slaRisk\\":\\"HIGH\\",\\"sentiment\\":\\"NEGATIVE\\",\\"summary\\":\\"Payment completed but the session did not begin.\\",\\"evidence\\":[{\\"quote\\":\\"Payment accepted\\",\\"meaning\\":\\"Payment was accepted.\\"}],\\"policyIds\\":[],\\"explanation\\":\\"Payment was accepted while the charging session did not begin, so service impact requires review.\\",\\"recommendedActions\\":[\\"Verify payment and session state\\"],\\"suggestedReply\\":\\"We are checking the payment and session state now.\\",\\"warnings\\":[]}"}}],"usage":{"prompt_tokens":12,"completion_tokens":8}}
                """;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes();
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
