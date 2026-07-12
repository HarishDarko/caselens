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
        assertThat(call.result().category()).isEqualTo(Category.BILLING);
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
