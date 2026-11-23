package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SqsWorkerPollerTest {
    @Mock SqsClient client;
    @Mock TriageWorker worker;

    @Test
    void deletesAcknowledgedMessagesButLeavesRetryableMessagesVisible() {
        UUID eventId = UUID.randomUUID();
        String body = """
                {"schemaVersion":1,"eventId":"%s","workspaceId":"%s","ticketId":"%s","correlationId":"corr"}
                """.formatted(eventId, UUID.randomUUID(), UUID.randomUUID());
        Message message = Message.builder().body(body).receiptHandle("receipt-1").build();
        when(client.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(message).build());
        when(worker.process(any(QueueMessage.class), anyInt())).thenReturn(WorkerResult.ack());

        new SqsWorkerPoller(client, "queue-url", worker, new ObjectMapper()).poll();

        ArgumentCaptor<DeleteMessageRequest> deleted = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(client).deleteMessage(deleted.capture());
        assertThat(deleted.getValue().receiptHandle()).isEqualTo("receipt-1");

        when(worker.process(any(QueueMessage.class), anyInt())).thenReturn(WorkerResult.retry("PROVIDER_TIMEOUT"));
        new SqsWorkerPoller(client, "queue-url", worker, new ObjectMapper()).poll();
        verify(client, org.mockito.Mockito.times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void leavesAMessageVisibleWhenProcessingThrows() {
        String body = """
                {"schemaVersion":1,"eventId":"%s","workspaceId":"%s","ticketId":"%s","correlationId":"corr"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Message message = Message.builder().body(body).receiptHandle("receipt-error").build();
        when(client.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(message).build());
        when(worker.process(any(QueueMessage.class), anyInt())).thenThrow(new IllegalArgumentException("thrown while processing"));

        new SqsWorkerPoller(client, "queue-url", worker, new ObjectMapper()).poll();

        verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void asksSqsForTheReceiveCountAndPassesItToTheWorker() {
        String body = """
                {"schemaVersion":1,"eventId":"%s","workspaceId":"%s","ticketId":"%s","correlationId":"corr"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Message message = Message.builder().body(body).receiptHandle("receipt-4")
                .attributes(java.util.Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "4")).build();
        when(client.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(message).build());
        when(worker.process(any(QueueMessage.class), anyInt())).thenReturn(WorkerResult.ack());

        new SqsWorkerPoller(client, "queue-url", worker, new ObjectMapper()).poll();

        ArgumentCaptor<ReceiveMessageRequest> request = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(client).receiveMessage(request.capture());
        assertThat(request.getValue().messageSystemAttributeNames())
                .contains(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        verify(worker).process(any(QueueMessage.class), org.mockito.ArgumentMatchers.eq(4));
    }

    @Test
    void deletesMalformedPoisonMessagesWithoutCallingTheWorker() {
        Message message = Message.builder().body("{\"message\":\"ticket body\"}").receiptHandle("receipt-bad").build();
        when(client.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(message).build());

        new SqsWorkerPoller(client, "queue-url", worker, new ObjectMapper()).poll();

        verify(worker, never()).process(any(QueueMessage.class), anyInt());
        verify(client).deleteMessage(any(DeleteMessageRequest.class));
    }
}
