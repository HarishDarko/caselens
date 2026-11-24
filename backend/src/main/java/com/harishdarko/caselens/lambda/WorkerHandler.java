package com.harishdarko.caselens.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.harishdarko.caselens.common.SensitiveDataSanitizer;
import com.harishdarko.caselens.triage.CorrelationIds;
import com.harishdarko.caselens.triage.QueueMessage;
import com.harishdarko.caselens.triage.QueueMessageCodec;
import com.harishdarko.caselens.triage.TriageWorker;
import com.harishdarko.caselens.triage.WorkerResultStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class WorkerHandler implements RequestStreamHandler {
    private static final Logger log = LoggerFactory.getLogger(WorkerHandler.class);
    private final ObjectMapper mapper;
    private final TriageWorker worker;
    private final QueueMessageCodec codec;

    public WorkerHandler() {
        this(LambdaSpringContext::mapper, () -> LambdaSpringContext.get().getBean(TriageWorker.class));
    }

    WorkerHandler(Supplier<ObjectMapper> mapperSupplier, Supplier<TriageWorker> workerSupplier) {
        this.mapper = mapperSupplier.get();
        this.worker = workerSupplier.get();
        this.codec = new QueueMessageCodec(mapper);
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        JsonNode records = mapper.readTree(input).path("Records");
        // As in RelayHandler: the worker cuts the provider call to what is left of this invocation.
        LongSupplier remainingMillis = context == null ? null : () -> context.getRemainingTimeInMillis();
        List<Map<String, String>> failures = retryableFailures(records, codec, worker, remainingMillis);
        mapper.writeValue(output, Map.of("batchItemFailures", failures));
    }

    /** SQS's ApproximateReceiveCount for this record, or 0 when it is missing or unreadable (treated as final). */
    static int receiveCount(JsonNode record) {
        String value = record.path("attributes").path("ApproximateReceiveCount").asText("");
        try {
            return value.isEmpty() ? 0 : Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }

    static List<Map<String, String>> retryableFailures(JsonNode records, QueueMessageCodec codec, TriageWorker worker) {
        return retryableFailures(records, codec, worker, null);
    }

    /**
     * Only a payload that cannot be decoded is dropped: retrying it can never succeed. Anything that goes wrong while
     * processing a decoded message (a database error, a bug, an IllegalArgumentException from deeper code) is
     * reported back as a batch item failure, so SQS redelivers it and, after maxReceiveCount, moves it to the DLQ
     * where the alarm sees it, instead of acknowledging it silently. {@code remainingMillis} is the invocation's
     * remaining time, or null when unknown.
     */
    static List<Map<String, String>> retryableFailures(JsonNode records, QueueMessageCodec codec, TriageWorker worker,
            LongSupplier remainingMillis) {
        List<Map<String, String>> failures = new ArrayList<>();
        for (JsonNode record : records) {
            String messageId = record.path("messageId").asText();
            QueueMessage message;
            try {
                message = codec.decode(record.path("body").asText());
            } catch (IllegalArgumentException malformedPayload) {
                log.warn("triage.malformed_message messageId={} reason={}", SensitiveDataSanitizer.text(messageId),
                        SensitiveDataSanitizer.text(malformedPayload.getMessage()));
                continue;
            }
            try {
                var result = worker.process(message, receiveCount(record), remainingMillis);
                if (result.status() == WorkerResultStatus.RETRY) failures.add(Map.of("itemIdentifier", messageId));
            } catch (RuntimeException processingFailure) {
                log.warn("triage.worker_failed messageId={} eventId={} correlationId={} errorType={}",
                        SensitiveDataSanitizer.text(messageId), message.eventId(), CorrelationIds.normalize(message.correlationId()),
                        processingFailure.getClass().getSimpleName());
                failures.add(Map.of("itemIdentifier", messageId));
            }
        }
        return failures;
    }
}
