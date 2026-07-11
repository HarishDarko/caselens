package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

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
        when(worker.process(any(QueueMessage.class))).thenReturn(WorkerResult.ack());

        new SqsWorkerPoller(client, "queue-url", worker, new ObjectMapper()).poll();

        ArgumentCaptor<DeleteMessageRequest> deleted = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(client).deleteMessage(deleted.capture());
        assertThat(deleted.getValue().receiptHandle()).isEqualTo("receipt-1");

        when(worker.process(any(QueueMessage.class))).thenReturn(WorkerResult.retry("PROVIDER_TIMEOUT"));
        new SqsWorkerPoller(client, "queue-url", worker, new ObjectMapper()).poll();
        verify(client, org.mockito.Mockito.times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void deletesMalformedPoisonMessagesWithoutCallingTheWorker() {
        Message message = Message.builder().body("{\"message\":\"ticket body\"}").receiptHandle("receipt-bad").build();
        when(client.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(message).build());

        new SqsWorkerPoller(client, "queue-url", worker, new ObjectMapper()).poll();

        verify(worker, never()).process(any(QueueMessage.class));
        verify(client).deleteMessage(any(DeleteMessageRequest.class));
    }
}
