package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.harishdarko.caselens.ticket.TicketChannel;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Golden check for the Gemini and Groq providers: the exact request body sent for one fixed ticket, and the exact
 * parsed result (or error) for fixed model outputs. The digest was taken on main before the shared schema refactor.
 * Set -Dcaselens.golden.out=<file> to write the full golden text for a diff.
 */
class ProviderRequestGoldenTest {
    private static final String EXPECTED_SHA256 = "014530508f702c1ebc3f8bda5ed439c931bd9bb7666ff7380200096a175dc595";
    private static final TriageRequest REQUEST = new TriageRequest(UUID.fromString("00000000-0000-0000-0000-000000000042"),
            "Payment accepted", "Payment accepted but session never started. Please check it.", TicketChannel.WEB, 0);
    private static final RedactedTicket REDACTED = new RedactedTicket(REQUEST.subject(), REQUEST.message());
    private static final String MODEL_OUTPUT = "{\"category\":\"BILLING\",\"urgency\":\"HIGH\",\"slaRisk\":\"HIGH\","
            + "\"sentiment\":\"NEGATIVE\",\"summary\":\"Payment completed but the session did not begin.\","
            + "\"evidence\":[{\"quote\":\"Payment accepted\",\"meaning\":\"Payment was accepted.\"},"
            + "{\"quote\":\"session never started\",\"meaning\":\"The session did not begin.\"}],"
            + "\"policyIds\":[\"PAYMENT_CAPTURE_NO_SESSION\"],"
            + "\"explanation\":\"Payment was accepted while the charging session did not begin, so service impact requires review.\","
            + "\"recommendedActions\":[\"Verify payment and session state\",\"Collect a transaction reference\"],"
            + "\"suggestedReply\":\"We are checking the payment and session state now.\",\"warnings\":[\"golden warning\"]}";
    private static final String BAD_OUTPUT = MODEL_OUTPUT.replace("\"BILLING\"", "\"NOT_A_CATEGORY\"");

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void providerRequestsAndParsedResultsMatchTheGolden() throws Exception {
        StringBuilder golden = new StringBuilder();
        golden.append(exchange("gemini gemini-3.5-flash", gemini("gemini-3.5-flash"), geminiResponse(MODEL_OUTPUT)));
        golden.append(exchange("gemini output_text", gemini("gemini-3.5-flash"),
                new ObjectMapper().createObjectNode().put("output_text", MODEL_OUTPUT).toString()));
        golden.append(exchange("gemini bad category", gemini("gemini-3.5-flash"), geminiResponse(BAD_OUTPUT)));
        golden.append(exchange("groq llama-3.1-8b-instant", groq("llama-3.1-8b-instant"), groqResponse(MODEL_OUTPUT)));
        golden.append(exchange("groq openai/gpt-oss-20b", groq("openai/gpt-oss-20b"), groqResponse(MODEL_OUTPUT)));
        golden.append(exchange("groq openai/gpt-oss-120b", groq("openai/gpt-oss-120b"), groqResponse(MODEL_OUTPUT)));
        golden.append(exchange("groq bad category", groq("llama-3.1-8b-instant"), groqResponse(BAD_OUTPUT)));

        String text = golden.toString();
        String out = System.getProperty("caselens.golden.out");
        if (out != null) Files.writeString(Path.of(out), text, StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        System.out.println("PROVIDER-GOLDEN sha256=" + digest + " chars=" + text.length());
        assertThat(digest).isEqualTo(EXPECTED_SHA256);
    }

    private String exchange(String label, ProviderFactory factory, String responseBody) throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/golden", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        String outcome;
        try {
            TriageProvider provider = factory.create(URI.create("http://localhost:" + server.getAddress().getPort() + "/golden"));
            ProviderCall call = provider.analyze(REQUEST, REDACTED);
            outcome = "result=" + call.result() + "\ninputTokens=" + call.inputTokens() + " outputTokens=" + call.outputTokens();
        } catch (ProviderCallException exception) {
            outcome = "error=" + exception.errorCode() + " message=" + exception.getMessage();
        } finally {
            server.stop(0);
            server = null;
        }
        return "## " + label + "\nrequest=" + body.get() + "\n" + outcome + "\n";
    }

    private ProviderFactory gemini(String model) {
        return endpoint -> new GeminiTriageProvider(endpoint, "golden-key", model, client(), new ObjectMapper(),
                Duration.ofSeconds(2), () -> 0);
    }

    private ProviderFactory groq(String model) {
        return endpoint -> new GroqTriageProvider(endpoint, "golden-key", model, client(), new ObjectMapper(),
                Duration.ofSeconds(2), () -> 0);
    }

    private HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    private String geminiResponse(String output) {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.putArray("steps").addObject().put("type", "model_output").putArray("content").addObject()
                .put("type", "text").put("text", output);
        root.putObject("usage").put("total_input_tokens", 12).put("total_output_tokens", 8);
        return root.toString();
    }

    private String groqResponse(String output) {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.putArray("choices").addObject().putObject("message").put("content", output);
        root.putObject("usage").put("prompt_tokens", 12).put("completion_tokens", 8);
        return root.toString();
    }

    private interface ProviderFactory {
        TriageProvider create(URI endpoint);
    }
}
