package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueueMessageCodecTest {
    private final QueueMessageCodec codec = new QueueMessageCodec(new ObjectMapper());

    @Test
    void decodesIdentifierOnlyEventPayload() {
        UUID eventId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        QueueMessage message = codec.decode("""
                {"schemaVersion":1,"eventId":"%s","workspaceId":"%s","ticketId":"%s","correlationId":"corr-1"}
                """.formatted(eventId, workspaceId, ticketId));

        assertThat(message).isEqualTo(new QueueMessage(eventId, workspaceId, ticketId, "corr-1", 1));
    }

    @Test
    void rejectsTicketBodyInQueuePayload() {
        assertThatThrownBy(() -> codec.decode("""
                {"schemaVersion":1,"eventId":"%s","workspaceId":"%s","ticketId":"%s","subject":"secret","message":"ticket body"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unexpected queue payload field");
    }
}
