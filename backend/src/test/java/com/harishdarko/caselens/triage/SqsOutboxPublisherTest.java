package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

class SqsOutboxPublisherTest {
    @Test
    void sendsIdentifierOnlyPayloadToConfiguredQueue() {
        SqsClient client = org.mockito.Mockito.mock(SqsClient.class);
        OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "corr", 
                Clock.fixed(Instant.parse("2026-07-11T14:00:00Z"), ZoneOffset.UTC).instant());

        new SqsOutboxPublisher(client, "http://localhost/queue").publish(event);

        verify(client).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void convertsSdkFailureIntoSanitizedQueueError() {
        SqsClient client = org.mockito.Mockito.mock(SqsClient.class);
        doThrow(SqsException.builder().statusCode(503).build()).when(client).sendMessage(any(SendMessageRequest.class));
        OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "corr", Instant.now());

        assertThatThrownBy(() -> new SqsOutboxPublisher(client, "http://localhost/queue").publish(event))
                .isInstanceOf(QueuePublishException.class)
                .hasMessage("Queue publish failed");
    }
}
