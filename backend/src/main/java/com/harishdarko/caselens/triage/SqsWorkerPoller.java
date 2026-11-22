package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.common.SensitiveDataSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("local")
@ConditionalOnProperty(name = "caselens.queue.enabled", havingValue = "true")
public class SqsWorkerPoller {
    private static final Logger log = LoggerFactory.getLogger(SqsWorkerPoller.class);
    private final SqsClient client;
    private final String queueUrl;
    private final TriageWorker worker;
    private final QueueMessageCodec codec;

    public SqsWorkerPoller(SqsClient client, @Value("${caselens.triage-queue-url}") String queueUrl,
            TriageWorker worker, ObjectMapper mapper) {
        this.client = client;
        this.queueUrl = queueUrl;
        this.worker = worker;
        this.codec = new QueueMessageCodec(mapper);
    }

    @Scheduled(fixedDelayString = "${caselens.worker.poll-delay-ms:1000}")
    public void poll() {
        var response = client.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1).build());
        for (Message message : response.messages()) {
            if (shouldDelete(message)) {
                client.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle()).build());
            }
        }
    }

    /** Same rule as the Lambda WorkerHandler: drop only payloads that cannot be decoded; redeliver on any other error. */
    private boolean shouldDelete(Message message) {
        QueueMessage decoded;
        try {
            decoded = codec.decode(message.body());
        } catch (IllegalArgumentException malformedPayload) {
            log.warn("triage.malformed_message messageId={} reason={}", SensitiveDataSanitizer.text(message.messageId()),
                    SensitiveDataSanitizer.text(malformedPayload.getMessage()));
            return true;
        }
        try {
            return worker.process(decoded).status() != WorkerResultStatus.RETRY;
        } catch (RuntimeException processingFailure) {
            log.warn("triage.worker_failed messageId={} eventId={} correlationId={} errorType={}",
                    SensitiveDataSanitizer.text(message.messageId()), decoded.eventId(), CorrelationIds.normalize(decoded.correlationId()),
                    processingFailure.getClass().getSimpleName());
            return false;
        }
    }
}
