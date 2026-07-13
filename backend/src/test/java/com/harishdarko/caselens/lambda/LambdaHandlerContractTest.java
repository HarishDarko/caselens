package com.harishdarko.caselens.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harishdarko.caselens.triage.QueueMessageCodec;
import com.harishdarko.caselens.triage.TriageWorker;
import com.harishdarko.caselens.triage.WorkerResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LambdaHandlerContractTest {
    @Test
    void exposesTheHandlersTerraformWillInvoke() {
        assertThat(List.of(ApiHandler.class, WorkerHandler.class, RelayHandler.class))
                .allSatisfy(handler -> assertThat(RequestStreamHandler.class).isAssignableFrom(handler));
    }

    @Test
    void decodesApiGatewayBodiesAndUsesTheEventSourceIp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String encoded = java.util.Base64.getEncoder().encodeToString("synthetic-body".getBytes(StandardCharsets.UTF_8));
        JsonNode event = mapper.readTree("""
                {"body":"%s","isBase64Encoded":true,
                 "requestContext":{"http":{"sourceIp":"198.51.100.40"}}}
                """.formatted(encoded));

        assertThat(ApiHandler.body(event)).isEqualTo("synthetic-body");
        assertThat(ApiHandler.sourceIp(event)).isEqualTo("198.51.100.40");
    }

    @Test
    void reportsOnlyRetryableSqsRecordsAndDropsMalformedPayloads() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UUID eventId = UUID.randomUUID();
        String valid = "{\"schemaVersion\":1,\"eventId\":\"%s\",\"workspaceId\":\"%s\",\"ticketId\":\"%s\",\"correlationId\":\"corr\"}"
                .formatted(eventId, UUID.randomUUID(), UUID.randomUUID());
        TriageWorker worker = mock(TriageWorker.class);
        when(worker.process(any())).thenReturn(WorkerResult.retry("PROVIDER_TIMEOUT"));
        JsonNode records = mapper.readTree("""
                {"Records":[
                  {"messageId":"retry-1","body":%s},
                  {"messageId":"terminal-1","body":"not-json"}
                ]}
                """.formatted(mapper.writeValueAsString(valid))).path("Records");

        assertThat(WorkerHandler.retryableFailures(records, new QueueMessageCodec(mapper), worker))
                .containsExactly(Map.of("itemIdentifier", "retry-1"));
    }

    @Test
    void workerBootstrapsSpringDependenciesWhenLambdaConstructsTheHandler() {
        ObjectMapper mapper = new ObjectMapper();
        TriageWorker worker = mock(TriageWorker.class);
        AtomicBoolean mapperLoaded = new AtomicBoolean();
        AtomicBoolean workerLoaded = new AtomicBoolean();

        new WorkerHandler(
                () -> {
                    mapperLoaded.set(true);
                    return mapper;
                },
                () -> {
                    workerLoaded.set(true);
                    return worker;
                });

        assertThat(mapperLoaded).isTrue();
        assertThat(workerLoaded).isTrue();
    }

    @Test
    void bridgesAnApiGatewayRequestAndSerializesTheProxyResponse() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ApiHandler handler = new ApiHandler((method, uri, headers, body) -> {
            assertThat(method).isEqualTo("POST");
            assertThat(uri).isEqualTo(URI.create("http://lambda.local/api/tickets?q=1"));
            assertThat(headers).containsEntry("Authorization", "Bearer synthetic-token");
            assertThat(body).isEqualTo("{\"subject\":\"Synthetic\"}");
            return new ApiHandler.ApiExchange(202, Map.of("X-CaseLens-Test", List.of("accepted")), "{\"ok\":true}");
        }, () -> "http://lambda.local");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        handler.handleRequest(new ByteArrayInputStream("""
                {"rawPath":"/api/tickets","rawQueryString":"q=1",
                 "requestContext":{"http":{"method":"POST","sourceIp":"198.51.100.40"}},
                 "headers":{"Authorization":"Bearer synthetic-token"},
                 "body":"{\\\"subject\\\":\\\"Synthetic\\\"}","isBase64Encoded":false}
                """.getBytes(StandardCharsets.UTF_8)), output, null);

        JsonNode response = mapper.readTree(output.toByteArray());
        assertThat(response.path("statusCode").asInt()).isEqualTo(202);
        assertThat(response.path("headers").path("X-CaseLens-Test").asText()).isEqualTo("accepted");
        assertThat(response.path("body").asText()).isEqualTo("{\"ok\":true}");
        assertThat(response.path("isBase64Encoded").asBoolean()).isFalse();
    }
}
