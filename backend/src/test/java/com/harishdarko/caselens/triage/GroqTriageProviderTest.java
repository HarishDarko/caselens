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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /**
     * A clock that moves only when time really passes: in the server handler (the request took that long) and in the
     * sleeper (the wait, plus 10 ms of overshoot, as Thread.sleep wakes late).
     */
    private static final class SteppedClock {
        final AtomicLong nanos = new AtomicLong();

        void advance(Duration by) { nanos.addAndGet(by.toNanos()); }

        LongSupplier nanoTime() { return nanos::get; }

        RateLimitWait.Sleeper sleeper(List<Long> sleeps) {
            return millis -> {
                sleeps.add(millis);
                advance(Duration.ofMillis(millis + 10));
            };
        }
    }

    @Test
    void approvesAWaitOnlyWhenTheRetryStillHasItsMinimumTimeAfterOvershoot() throws Exception {
        // Budget 45 s: first request 3 s, then wait + 1 s overshoot margin + 2 s connect + 5 s retry. 34 s fits exactly.
        assertThat(boundaryRun("34s").sleeps()).containsExactly(34_000L);
        assertThat(boundaryRun("34s").calls()).isEqualTo(2);
        BoundaryRun over = boundaryRun("34.001s");
        assertThat(over.sleeps()).as("one millisecond more does not fit").isEmpty();
        assertThat(over.calls()).isEqualTo(1);
        assertThat(over.errorCode()).isEqualTo("HTTP_429");
    }

    private record BoundaryRun(List<Long> sleeps, int calls, String errorCode) {}

    private BoundaryRun boundaryRun(String resetTokens) throws Exception {
        if (server != null) server.stop(0);
        SteppedClock clock = new SteppedClock();
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            clock.advance(Duration.ofSeconds(3));
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("x-ratelimit-reset-tokens", resetTokens);
                respond(exchange, 429, "rate limited");
            } else {
                respond(exchange, 200, validResponse());
            }
        });
        server.start();
        List<Long> sleeps = new ArrayList<>();
        String errorCode = null;
        try {
            timedProvider(clock.nanoTime(), clock.sleeper(sleeps)).analyze(request(), ticket());
        } catch (ProviderCallException exception) {
            errorCode = exception.errorCode();
        }
        return new BoundaryRun(sleeps, calls.get(), errorCode);
    }

    @Test
    void countsTheTimeAlreadySpentAgainstTheWait() throws Exception {
        SteppedClock clock = new SteppedClock();
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            clock.advance(Duration.ofSeconds(30)); // a slow first answer
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("retry-after", "8");
            respond(exchange, 429, "rate limited");
        });
        server.start();
        List<Long> sleeps = new ArrayList<>();

        // 8 s alone fits the 45 s budget; after 30 s already spent, 30 + 8 + 1 + 2 + 5 = 46 does not.
        assertThatThrownBy(() -> timedProvider(clock.nanoTime(), clock.sleeper(sleeps)).analyze(request(), ticket()))
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("HTTP_429");
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aWaitLongerThanOneRedeliveryCouldCureFallsBackAtOnce() throws Exception {
        for (Map<String, String> headers : List.of(Map.of("retry-after", "128"),
                Map.of("x-ratelimit-remaining-requests", "0", "x-ratelimit-reset-requests", "7h12m3s"))) {
            if (server != null) server.stop(0);
            AtomicInteger calls = new AtomicInteger();
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/openai/v1/chat/completions", exchange -> {
                calls.incrementAndGet();
                headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
                respond(exchange, 429, "rate limited");
            });
            server.start();
            List<Long> sleeps = new ArrayList<>();

            assertThatThrownBy(() -> timedProvider(() -> 0L, sleeps::add).analyze(request(), ticket()))
                    .as(headers.toString())
                    .extracting(exception -> ((ProviderCallException) exception).errorCode())
                    .isEqualTo("RATE_LIMIT_LONG_WAIT");
            assertThat(calls).hasValue(1);
            assertThat(sleeps).isEmpty();
        }
    }

    @Test
    void aZeroWaitStillWaitsTheJitterBeforeRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("retry-after", "0");
                respond(exchange, 429, "rate limited");
            } else {
                respond(exchange, 200, validResponse());
            }
        });
        server.start();
        List<Long> sleeps = new ArrayList<>();

        new GroqTriageProvider(URI.create("http://localhost:" + server.getAddress().getPort() + "/openai/v1/chat/completions"),
                "secret-key", "llama-3.1-8b-instant", HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(), Duration.ofSeconds(2), () -> 40, GroqTriageProvider.CALL_BUDGET, () -> 0L, sleeps::add,
                Clock.systemUTC()).analyze(request(), ticket());

        assertThat(sleeps).containsExactly(40L);
        assertThat(calls).hasValue(2);
    }

    @Test
    void waitsForAnHttpDateRetryAfter() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("retry-after", "Mon, 23 Nov 2026 12:00:06 GMT");
                respond(exchange, 429, "rate limited");
            } else {
                respond(exchange, 200, validResponse());
            }
        });
        server.start();
        List<Long> sleeps = new ArrayList<>();

        new GroqTriageProvider(URI.create("http://localhost:" + server.getAddress().getPort() + "/openai/v1/chat/completions"),
                "secret-key", "llama-3.1-8b-instant", HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(), Duration.ofSeconds(2), () -> 0, GroqTriageProvider.CALL_BUDGET, () -> 0L, sleeps::add,
                Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC)).analyze(request(), ticket());

        assertThat(sleeps).containsExactly(6_000L);
    }

    @Test
    void theWorkersTimeBudgetCapsTheCallBudget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("retry-after", "5");
            respond(exchange, 429, "rate limited");
        });
        server.start();
        List<Long> sleeps = new ArrayList<>();
        TriageRequest withTenSeconds = request().withTimeBudget(Duration.ofSeconds(10));

        // 5 s fits the 45 s budget, but the Lambda has only 10 s to give: 5 + 1 + 2 + 5 > 10.
        assertThatThrownBy(() -> timedProvider(() -> 0L, sleeps::add).analyze(withTenSeconds, ticket()))
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("HTTP_429");
        assertThat(sleeps).isEmpty();
        assertThat(calls).hasValue(1);
    }

    @Test
    void aTruncatedAnswerHasItsOwnCode() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> respond(exchange, 200,
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\\\"category\\\":\\\"BILL\"}}]}"));
        server.start();

        assertThatThrownBy(() -> provider().analyze(request(), ticket()))
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("RESPONSE_TRUNCATED");
    }

    @Test
    void neverClampsANegativeZeroOrTinyTimeBudgetUpToAnHttpCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, validResponse());
        });
        server.start();
        List<Long> sleeps = new ArrayList<>();

        for (Duration insufficient : List.of(Duration.ofSeconds(-4), Duration.ZERO, Duration.ofMillis(2_999))) {
            assertThatThrownBy(() -> timedProvider(() -> 0L, sleeps::add)
                    .analyze(request().withTimeBudget(insufficient), ticket()))
                    .as("budget=" + insufficient)
                    .isInstanceOf(ProviderCallException.class)
                    .extracting(exception -> ((ProviderCallException) exception).errorCode())
                    .isEqualTo("TIME_BUDGET_EXHAUSTED");
        }
        assertThat(calls).as("must never send an HTTP request below connect + min first timeout").hasValue(0);
        assertThat(sleeps).isEmpty();

        // Exactly connect allowance (2 s) + minimum first timeout (1 s) = 3 s is allowed to run.
        ProviderCall exact = timedProvider(() -> 0L, sleeps::add)
                .analyze(request().withTimeBudget(Duration.ofSeconds(3)), ticket());
        assertThat(exact.result().category()).isEqualTo(Category.of("BILLING"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void aLongWaitRateLimitOnTheSecondAttemptAlsoGivesUpImmediately() throws Exception {
        for (int firstStatus : List.of(503, 429)) {
            if (server != null) server.stop(0);
            AtomicInteger calls = new AtomicInteger();
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/openai/v1/chat/completions", exchange -> {
                if (calls.incrementAndGet() == 1) {
                    if (firstStatus == 429) exchange.getResponseHeaders().add("retry-after", "1");
                    respond(exchange, firstStatus, "transient");
                } else {
                    exchange.getResponseHeaders().add("retry-after", "25920");
                    respond(exchange, 429, "daily quota spent");
                }
            });
            server.start();

            assertThatThrownBy(() -> timedProvider(() -> 0L, millis -> { }).analyze(request(), ticket()))
                    .as("firstStatus=" + firstStatus)
                    .extracting(exception -> ((ProviderCallException) exception).errorCode())
                    .isEqualTo("RATE_LIMIT_LONG_WAIT");
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void fastRateLimitWithinRedeliveryWindowRetriesLaterInsteadOfGivingUp() throws Exception {
        SteppedClock clock = new SteppedClock();
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            clock.advance(Duration.ofSeconds(1));
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("retry-after", "80");
            respond(exchange, 429, "rate limited");
        });
        server.start();
        List<Long> sleeps = new ArrayList<>();

        // At t = 1 s, an 80 s wait clears at t = 81 s, before the 90 s SQS redelivery even begins.
        assertThatThrownBy(() -> timedProvider(clock.nanoTime(), clock.sleeper(sleeps)).analyze(request(), ticket()))
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("HTTP_429");
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aSleepOvershootPastTheReserveMarginSkipsTheRetryWithoutSendingASecondCall() throws Exception {
        SteppedClock clock = new SteppedClock();
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            clock.advance(Duration.ofSeconds(3));
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("retry-after", "34");
            respond(exchange, 429, "rate limited");
        });
        server.start();
        List<Long> sleeps = new ArrayList<>();
        // Extreme OS pause: sleeper wakes 1.1 s late, leaving 45 - 3 - 35.1 - 2 = 4.9 s (< MIN_RETRY_TIMEOUT = 5 s).
        RateLimitWait.Sleeper lateSleeper = millis -> {
            sleeps.add(millis);
            clock.advance(Duration.ofMillis(millis + 1_100));
        };

        assertThatThrownBy(() -> timedProvider(clock.nanoTime(), lateSleeper).analyze(request(), ticket()))
                .extracting(exception -> ((ProviderCallException) exception).errorCode())
                .isEqualTo("HTTP_429");
        assertThat(sleeps).containsExactly(34_000L);
        assertThat(calls).as("must abort post-sleep when remaining timeout is below MIN_RETRY_TIMEOUT").hasValue(1);
    }

    private GroqTriageProvider timedProvider(LongSupplier nanoTime, RateLimitWait.Sleeper sleeper) {
        return new GroqTriageProvider(URI.create("http://localhost:" + server.getAddress().getPort()
                        + "/openai/v1/chat/completions"), "secret-key", "llama-3.1-8b-instant",
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), new ObjectMapper(),
                Duration.ofSeconds(20), () -> 0, GroqTriageProvider.CALL_BUDGET, nanoTime, sleeper);
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
