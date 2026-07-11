package com.harishdarko.caselens.triage;

import java.util.Objects;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class SqsOutboxPublisher implements OutboxPublisher {
    private final SqsClient client;
    private final String queueUrl;

    public SqsOutboxPublisher(SqsClient client, String queueUrl) {
        this.client = Objects.requireNonNull(client);
        if (queueUrl == null || queueUrl.isBlank()) throw new IllegalArgumentException("Queue URL is required");
        this.queueUrl = queueUrl;
    }

    @Override
    public void publish(OutboxEvent event) {
        try {
            client.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(event.getPayload()).build());
        } catch (SdkException failure) {
            throw new QueuePublishException("QUEUE_TRANSIENT");
        }
    }
}
