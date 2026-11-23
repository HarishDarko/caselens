package com.harishdarko.caselens.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.harishdarko.caselens.demo.DemoRetentionSweep;
import com.harishdarko.caselens.triage.OutboxRelay;
import com.harishdarko.caselens.triage.QueueMessageCodec;
import com.harishdarko.caselens.triage.TriageWorker;
import com.harishdarko.caselens.triage.WorkerResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
        when(worker.process(any(), anyInt())).thenReturn(WorkerResult.retry("PROVIDER_TIMEOUT"));
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
    void reportsProcessingErrorsForRedeliveryInsteadOfAcknowledgingThem() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UUID illegalArgument = UUID.randomUUID();
        UUID databaseDown = UUID.randomUUID();
        UUID completed = UUID.randomUUID();
        TriageWorker worker = mock(TriageWorker.class);
        when(worker.process(any(), anyInt())).thenAnswer(invocation -> {
            UUID eventId = ((com.harishdarko.caselens.triage.QueueMessage) invocation.getArgument(0)).eventId();
            if (eventId.equals(illegalArgument)) throw new IllegalArgumentException("thrown deep inside processing");
            if (eventId.equals(databaseDown)) throw new IllegalStateException("database unavailable");
            return WorkerResult.ack();
        });
        JsonNode records = mapper.readTree("""
                {"Records":[
                  {"messageId":"iae-1","body":%s},
                  {"messageId":"db-1","body":%s},
                  {"messageId":"ok-1","body":%s},
                  {"messageId":"malformed-1","body":"{}"}
                ]}
                """.formatted(body(mapper, illegalArgument), body(mapper, databaseDown), body(mapper, completed)))
                .path("Records");

        assertThat(WorkerHandler.retryableFailures(records, new QueueMessageCodec(mapper), worker))
                .containsExactly(Map.of("itemIdentifier", "iae-1"), Map.of("itemIdentifier", "db-1"));
    }

    @Test
    void passesSqsReceiveCountToTheWorkerSoItKnowsTheFinalDelivery() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TriageWorker worker = mock(TriageWorker.class);
        when(worker.process(any(), anyInt())).thenReturn(WorkerResult.ack());
        JsonNode records = mapper.readTree("""
                {"Records":[
                  {"messageId":"third","attributes":{"ApproximateReceiveCount":"3"},"body":%s},
                  {"messageId":"unknown","body":%s}
                ]}
                """.formatted(body(mapper, UUID.randomUUID()), body(mapper, UUID.randomUUID()))).path("Records");

        WorkerHandler.retryableFailures(records, new QueueMessageCodec(mapper), worker);

        org.mockito.Mockito.verify(worker).process(any(), org.mockito.ArgumentMatchers.eq(3));
        org.mockito.Mockito.verify(worker).process(any(), org.mockito.ArgumentMatchers.eq(0));
    }

    private static String body(ObjectMapper mapper, UUID eventId) throws Exception {
        return mapper.writeValueAsString("{\"schemaVersion\":1,\"eventId\":\"%s\",\"workspaceId\":\"%s\",\"ticketId\":\"%s\",\"correlationId\":\"corr\"}"
                .formatted(eventId, UUID.randomUUID(), UUID.randomUUID()));
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
    void relayBootstrapsSpringDependenciesWhenLambdaConstructsTheHandler() {
        AtomicBoolean mapperLoaded = new AtomicBoolean();
        AtomicBoolean relayLoaded = new AtomicBoolean();

        new RelayHandler(
                () -> {
                    mapperLoaded.set(true);
                    return new ObjectMapper();
                },
                () -> {
                    relayLoaded.set(true);
                    return mock(OutboxRelay.class);
                },
                () -> mock(DemoRetentionSweep.class));

        assertThat(mapperLoaded).isTrue();
        assertThat(relayLoaded).isTrue();
    }

    @Test
    void relayPublishesOneBatchPurgesExpiredDataAndReportsTheCounts() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OutboxRelay relay = mock(OutboxRelay.class);
        when(relay.publishBatch(org.mockito.ArgumentMatchers.eq(RelayHandler.BATCH_SIZE), any())).thenReturn(3);
        DemoRetentionSweep sweep = mock(DemoRetentionSweep.class);
        when(sweep.sweep(RelayHandler.SWEEP_BUDGET)).thenReturn(new DemoRetentionSweep.Result(4, 9));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new RelayHandler(() -> mapper, () -> relay, () -> sweep)
                .handleRequest(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), output, null);

        JsonNode response = mapper.readTree(output.toByteArray());
        assertThat(response.path("published").asInt()).isEqualTo(3);
        assertThat(response.path("purgedWorkspaces").asInt()).isEqualTo(4);
        assertThat(response.path("purgedRateLimitBuckets").asInt()).isEqualTo(9);
        assertThat(response.path("status").asText()).isEqualTo("OK");
    }

    @Test
    void relaySweepGetsOnlyTheTimeLeftAfterTheOutboxStep() throws Exception {
        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(29_000);
        com.amazonaws.services.lambda.runtime.Context context = mock(com.amazonaws.services.lambda.runtime.Context.class);
        when(context.getRemainingTimeInMillis()).thenAnswer(invocation -> remaining.get());
        OutboxRelay relay = mock(OutboxRelay.class);
        when(relay.publishBatch(org.mockito.ArgumentMatchers.eq(RelayHandler.BATCH_SIZE), any())).thenAnswer(invocation -> {
            remaining.set(9_000); // a slow publish used 20 s
            return 25;
        });
        DemoRetentionSweep sweep = mock(DemoRetentionSweep.class);
        when(sweep.sweep(any())).thenReturn(new DemoRetentionSweep.Result(0, 0));

        new RelayHandler(ObjectMapper::new, () -> relay, () -> sweep)
                .handleRequest(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream(),
                        context);

        org.mockito.Mockito.verify(sweep).sweep(java.time.Duration.ofSeconds(1));
    }

    @Test
    void relaySweepBudgetStaysInsideTheRemainingFunctionTime() {
        com.amazonaws.services.lambda.runtime.Context plenty = mock(com.amazonaws.services.lambda.runtime.Context.class);
        when(plenty.getRemainingTimeInMillis()).thenReturn(29_000);
        com.amazonaws.services.lambda.runtime.Context tight = mock(com.amazonaws.services.lambda.runtime.Context.class);
        when(tight.getRemainingTimeInMillis()).thenReturn(12_000);
        com.amazonaws.services.lambda.runtime.Context spent = mock(com.amazonaws.services.lambda.runtime.Context.class);
        when(spent.getRemainingTimeInMillis()).thenReturn(5_000);

        assertThat(RelayHandler.sweepBudget(plenty)).isEqualTo(RelayHandler.SWEEP_BUDGET);
        assertThat(RelayHandler.sweepBudget(tight)).isEqualTo(java.time.Duration.ofSeconds(4));
        assertThat(RelayHandler.sweepBudget(spent)).isEqualTo(java.time.Duration.ZERO);
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
