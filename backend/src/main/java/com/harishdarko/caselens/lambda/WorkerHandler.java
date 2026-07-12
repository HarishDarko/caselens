package com.harishdarko.caselens.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
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

public final class WorkerHandler implements RequestStreamHandler {
    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        ObjectMapper mapper = LambdaSpringContext.mapper();
        TriageWorker worker = LambdaSpringContext.get().getBean(TriageWorker.class);
        QueueMessageCodec codec = new QueueMessageCodec(mapper);
        JsonNode records = mapper.readTree(input).path("Records");
        List<Map<String, String>> failures = retryableFailures(records, codec, worker);
        mapper.writeValue(output, Map.of("batchItemFailures", failures));
    }

    static List<Map<String, String>> retryableFailures(JsonNode records, QueueMessageCodec codec, TriageWorker worker) {
        List<Map<String, String>> failures = new ArrayList<>();
        for (JsonNode record : records) {
            String messageId = record.path("messageId").asText();
            try {
                var result = worker.process(codec.decode(record.path("body").asText()));
                if (result.status() == WorkerResultStatus.RETRY) failures.add(Map.of("itemIdentifier", messageId));
            } catch (IllegalArgumentException malformedPayload) {
                // Malformed messages are terminal and must not be retried forever.
            }
        }
        return failures;
    }
}
