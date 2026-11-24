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
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GeminiTriageProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsStatelessStructuredRequestAndRetriesOneTransientResponse() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/interactions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes()));
            apiKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            int call = calls.incrementAndGet();
            if (call == 1) respond(exchange, 429, "busy");
            else respond(exchange, 200, validResponse());
        });
        server.start();

        GeminiTriageProvider provider = provider();
        ProviderCall call = provider.analyze(request(), new RedactedTicket(
                "Payment accepted", "Payment accepted but session never started."));

        assertThat(calls).hasValue(2);
        assertThat(call.result().category()).isEqualTo(Category.of("BILLING"));
        assertThat(call.inputTokens()).isEqualTo(12L);
        assertThat(call.outputTokens()).isEqualTo(8L);
        assertThat(body.get()).contains("\"store\":false");
        assertThat(body.get()).contains("\"response_format\"");
        assertThat(body.get()).contains("\"additionalProperties\":false");
        assertThat(body.get()).contains("PAYMENT_CAPTURE_NO_SESSION");
        assertThat(apiKey.get()).isEqualTo("secret-key");
        assertThat(body.get()).doesNotContain("secret-key");
    }

    @Test
    void doesNotRetryAuthenticationFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/interactions", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 401, "unauthorized");
        });
        server.start();

        assertThatThrownBy(() -> provider().analyze(request(), new RedactedTicket("Payment accepted", "Payment accepted but session never started.")))
                .isInstanceOf(ProviderCallException.class)
                .hasMessage("Gemini provider request was rejected")
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("AUTHENTICATION_FAILURE");
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsAnOversizedProviderResponseBeforeParsing() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/interactions", exchange -> respond(exchange, 200, "x".repeat(65_537)));
        server.start();

        assertThatThrownBy(() -> provider().analyze(request(), new RedactedTicket(
                "Payment accepted", "Payment accepted but session never started.")))
                .isInstanceOf(ProviderCallException.class)
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("RESPONSE_TOO_LARGE");
    }

    @Test
    void skipsTheRetryWhenTooLittleOfTheCallBudgetIsLeft() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/interactions", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 503, "slow and unavailable");
        });
        server.start();
        // The first attempt "takes" 40 s of the 45 s budget, so 3 s would be left for a retry: not enough.
        AtomicInteger clockReads = new AtomicInteger();
        GeminiTriageProvider provider = new GeminiTriageProvider(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/interactions"),
                "secret-key", "gemini-3.5-flash", HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(), Duration.ofSeconds(30), () -> 0, GeminiTriageProvider.CALL_BUDGET,
                () -> clockReads.getAndIncrement() < 2 ? 0L : Duration.ofSeconds(40).toNanos());

        assertThatThrownBy(() -> provider.analyze(request(), new RedactedTicket(
                "Payment accepted", "Payment accepted but session never started.")))
                .isInstanceOf(ProviderCallException.class)
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("HTTP_503");
        assertThat(calls).hasValue(1);
    }

    @Test
    void cutsEachAttemptTimeoutToTheRemainingCallBudget() {
        long[] now = {0};
        GeminiTriageProvider provider = new GeminiTriageProvider(URI.create("http://localhost:1/v1/interactions"),
                "secret-key", "gemini-3.5-flash", HttpClient.newHttpClient(), new ObjectMapper(), Duration.ofSeconds(30),
                () -> 0, GeminiTriageProvider.CALL_BUDGET, () -> now[0]);

        assertThat(provider.attemptTimeout(0)).isEqualTo(Duration.ofSeconds(30));
        now[0] = Duration.ofSeconds(32).toNanos();
        // 45 s budget - 32 s spent - 2 s connect allowance: the retry gets 11 s, so the whole call stays under 60 s.
        assertThat(provider.attemptTimeout(0)).isEqualTo(Duration.ofSeconds(11));
    }

    @Test
    void followsTheSameRateLimitRuleAsGroq() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> retryAfter = new AtomicReference<>("3");
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/interactions", exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("retry-after", retryAfter.get());
                respond(exchange, 429, "rate limited");
            } else {
                respond(exchange, 200, validResponse());
            }
        });
        server.start();
        List<Long> sleeps = new ArrayList<>();
        GeminiTriageProvider provider = new GeminiTriageProvider(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/interactions"),
                "secret-key", "gemini-3.5-flash", HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(), Duration.ofSeconds(30), () -> 0, GeminiTriageProvider.CALL_BUDGET, () -> 0L,
                sleeps::add, Clock.systemUTC());
        RedactedTicket ticket = new RedactedTicket("Payment accepted", "Payment accepted but session never started.");

        provider.analyze(request(), ticket);
        assertThat(sleeps).as("waits retry-after, then retries in the call").containsExactly(3_000L);
        assertThat(calls).hasValue(2);

        calls.set(0);
        sleeps.clear();
        retryAfter.set("128");
        assertThatThrownBy(() -> provider.analyze(request(), ticket))
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("RATE_LIMIT_LONG_WAIT");
        assertThat(sleeps).isEmpty();
        assertThat(calls).hasValue(1);

        calls.set(0);
        retryAfter.set("39");
        assertThatThrownBy(() -> provider.analyze(request(), ticket))
                .as("a wait that fits no retry in this call is left for SQS")
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("HTTP_429");
        assertThat(calls).hasValue(1);
    }

    @Test
    void neverClampsAZeroOrTinyTimeBudgetUpToAnHttpCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/interactions", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, validResponse());
        });
        server.start();
        GeminiTriageProvider provider = new GeminiTriageProvider(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/interactions"),
                "secret-key", "gemini-3.5-flash", HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(), Duration.ofSeconds(30), () -> 0, GeminiTriageProvider.CALL_BUDGET, () -> 0L,
                millis -> { }, Clock.systemUTC());
        RedactedTicket ticket = new RedactedTicket("Payment accepted", "Payment accepted but session never started.");

        for (Duration insufficient : List.of(Duration.ofSeconds(-4), Duration.ZERO, Duration.ofMillis(2_999))) {
            assertThatThrownBy(() -> provider.analyze(request().withTimeBudget(insufficient), ticket))
                    .extracting(exception -> ((ProviderCallException) exception).errorCode())
                    .isEqualTo("TIME_BUDGET_EXHAUSTED");
        }
        assertThat(calls).hasValue(0);
    }

    @Test
    void aLongWaitRateLimitOnTheSecondAttemptAlsoGivesUpImmediately() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/interactions", exchange -> {
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 503, "transient");
            } else {
                exchange.getResponseHeaders().add("retry-after", "25920");
                respond(exchange, 429, "daily limit");
            }
        });
        server.start();
        GeminiTriageProvider provider = new GeminiTriageProvider(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/interactions"),
                "secret-key", "gemini-3.5-flash", HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(), Duration.ofSeconds(30), () -> 0, GeminiTriageProvider.CALL_BUDGET, () -> 0L,
                millis -> { }, Clock.systemUTC());

        assertThatThrownBy(() -> provider.analyze(request(),
                new RedactedTicket("Payment accepted", "Payment accepted but session never started.")))
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("RATE_LIMIT_LONG_WAIT");
        assertThat(calls).hasValue(2);
    }

    private GeminiTriageProvider provider() {
        return new GeminiTriageProvider(URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/interactions"),
                "secret-key", "gemini-3.5-flash", HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(), Duration.ofSeconds(2), () -> 0);
    }

    private TriageRequest request() {
        return new TriageRequest(UUID.randomUUID(), "Payment accepted", "Payment accepted but session never started.",
                TicketChannel.WEB, 0);
    }

    private String validResponse() {
        return """
                {"steps":[{"type":"model_output","content":[{"type":"text","text":"{\\"category\\":\\"BILLING\\",\\"urgency\\":\\"HIGH\\",\\"slaRisk\\":\\"HIGH\\",\\"sentiment\\":\\"NEGATIVE\\",\\"summary\\":\\"Payment completed but the session did not begin.\\",\\"evidence\\":[{\\"quote\\":\\"Payment accepted\\",\\"meaning\\":\\"Payment was accepted.\\"}],\\"policyIds\\":[],\\"explanation\\":\\"Payment was accepted while the charging session did not begin, so service impact requires review.\\",\\"recommendedActions\\":[\\"Verify payment and session state\\"],\\"suggestedReply\\":\\"We are checking the payment and session state now.\\",\\"warnings\\":[]}"}]}],"usage":{"total_input_tokens":12,"total_output_tokens":8}}
                """;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes();
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
