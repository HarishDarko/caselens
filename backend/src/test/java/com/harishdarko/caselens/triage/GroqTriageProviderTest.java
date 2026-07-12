package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.harishdarko.caselens.ticket.TicketChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
        assertThat(call.result().category()).isEqualTo(Category.BILLING);
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
