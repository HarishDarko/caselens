package com.harishdarko.caselens.triage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Component
@Profile("local")
@ConditionalOnProperty(name = "caselens.queue.enabled", havingValue = "true")
public class SqsWorkerPoller {
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

    private boolean shouldDelete(Message message) {
        try {
            WorkerResult result = worker.process(codec.decode(message.body()));
            return result.status() != WorkerResultStatus.RETRY;
        } catch (IllegalArgumentException malformedPayload) {
            return true;
        }
    }
}
