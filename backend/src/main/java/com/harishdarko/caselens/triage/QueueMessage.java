package com.harishdarko.caselens.triage;

import java.util.Objects;
import java.util.UUID;

public record QueueMessage(UUID eventId, UUID workspaceId, UUID ticketId, String correlationId, int schemaVersion) {
    public QueueMessage {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(workspaceId);
        Objects.requireNonNull(ticketId);
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported queue schema version");
    }
}
