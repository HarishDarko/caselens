package com.harishdarko.caselens.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Inputs from the CL-06 and CL-17 probes. Each used to throw IllegalArgumentException out of the handler, which Lambda
 * counted as a function error. Now a malformed request is a 400 problem response and a restricted header is dropped.
 */
class ApiHandlerInputTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<Map<String, String>> forwarded = new AtomicReference<>();

    /** Builds the request exactly as the production transport does, without sending it. */
    private final ApiHandler handler = new ApiHandler((method, uri, headers, body) -> {
        ApiHandler.request(method, uri, headers, body);
        forwarded.set(headers);
        return new ApiHandler.ApiExchange(200, Map.of(), "{}");
    }, () -> "http://127.0.0.1:8080");

    @ParameterizedTest
    @ValueSource(strings = {"/api/tickets/{", "/api/tickets/|", "/api/tickets/^", "/api/tickets/%zz"})
    void answersBadRequestForAPathTheUriParserRejects(String rawPath) throws Exception {
        JsonNode response = invoke("""
                {"rawPath":%s,"requestContext":{"http":{"method":"GET","sourceIp":"198.51.100.40"}}}
                """.formatted(mapper.writeValueAsString(rawPath)));

        assertBadRequest(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"q={", "q=|", "q=^", "q=%zz"})
    void answersBadRequestForAQueryTheUriParserRejects(String rawQuery) throws Exception {
        JsonNode response = invoke("""
                {"rawPath":"/api/tickets","rawQueryString":%s,
                 "requestContext":{"http":{"method":"GET","sourceIp":"198.51.100.40"}}}
                """.formatted(mapper.writeValueAsString(rawQuery)));

        assertBadRequest(response);
    }

    @Test
    void dropsExpectAndOtherRestrictedOrHopByHopHeaders() throws Exception {
        JsonNode response = invoke("""
                {"rawPath":"/api/tickets","requestContext":{"http":{"method":"POST","sourceIp":"198.51.100.40"}},
                 "headers":{"Expect":"100-continue","Upgrade":"h2c","Connection":"keep-alive","Keep-Alive":"timeout=5",
                            "TE":"trailers","Transfer-Encoding":"chunked","Authorization":"Bearer synthetic-token",
                            "X-CaseLens-Client-Ip":"203.0.113.9"},
                 "body":"{}"}
                """);

        assertThat(response.path("statusCode").asInt()).isEqualTo(200);
        assertThat(forwarded.get()).containsOnlyKeys("Authorization", "X-CaseLens-Client-Ip");
        assertThat(forwarded.get()).containsEntry("X-CaseLens-Client-Ip", "198.51.100.40");
    }

    @Test
    void answersBadRequestForAHeaderNameOrValueTheHttpClientRejects() throws Exception {
        assertBadRequest(invoke("""
                {"rawPath":"/api/tickets","requestContext":{"http":{"method":"GET"}},
                 "headers":{"Bad Header":"x"}}
                """));
        assertBadRequest(invoke("""
                {"rawPath":"/api/tickets","requestContext":{"http":{"method":"GET"}},
                 "headers":{"X-Note":"line\\r\\nInjected: yes"}}
                """));
    }

    @Test
    void answersBadRequestForABodyThatIsNotBase64() throws Exception {
        assertBadRequest(invoke("""
                {"rawPath":"/api/tickets","requestContext":{"http":{"method":"POST"}},
                 "body":"not base64!","isBase64Encoded":true}
                """));
    }

    @Test
    void stillForwardsAnOrdinaryRequest() throws Exception {
        JsonNode response = invoke("""
                {"rawPath":"/api/tickets","rawQueryString":"page=0&size=20",
                 "requestContext":{"http":{"method":"GET"}},"headers":{"Accept":"application/json"}}
                """);

        assertThat(response.path("statusCode").asInt()).isEqualTo(200);
        assertThat(ApiHandler.request("GET", URI.create("http://127.0.0.1:8080/api/tickets?page=0&size=20"),
                Map.of("Accept", "application/json"), "").headers().allValues("Accept"))
                .isEqualTo(List.of("application/json"));
    }

    private JsonNode invoke(String event) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        handler.handleRequest(new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)), output, null);
        return mapper.readTree(output.toByteArray());
    }

    private void assertBadRequest(JsonNode response) throws Exception {
        assertThat(response.path("statusCode").asInt()).isEqualTo(400);
        assertThat(response.path("headers").path("Content-Type").asText()).isEqualTo("application/problem+json");
        JsonNode problem = mapper.readTree(response.path("body").asText());
        assertThat(problem.path("status").asInt()).isEqualTo(400);
        assertThat(problem.path("title").asText()).isEqualTo("Bad request");
    }
}
