package com.harishdarko.caselens.triage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

public class QueueMessageCodec {
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "schemaVersion", "eventId", "workspaceId", "ticketId", "correlationId");

    private final ObjectMapper mapper;

    public QueueMessageCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public QueueMessage decode(String payload) {
        try {
            JsonNode root = mapper.readTree(payload);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("Queue payload must be an object");
            Iterator<String> fields = root.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!ALLOWED_FIELDS.contains(field)) {
                    throw new IllegalArgumentException("Unexpected queue payload field: " + field);
                }
            }
            return new QueueMessage(requiredUuid(root, "eventId"), requiredUuid(root, "workspaceId"),
                    requiredUuid(root, "ticketId"), requiredText(root, "correlationId"),
                    requiredInt(root, "schemaVersion"));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Queue payload is not valid JSON", exception);
        }
    }

    private UUID requiredUuid(JsonNode root, String field) {
        return UUID.fromString(requiredText(root, field));
    }

    private String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Queue payload field is required: " + field);
        }
        return value.textValue();
    }

    private int requiredInt(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new IllegalArgumentException("Queue payload field is required: " + field);
        }
        return value.intValue();
    }
}
