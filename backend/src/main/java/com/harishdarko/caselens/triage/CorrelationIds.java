package com.harishdarko.caselens.triage;

import java.util.UUID;

public final class CorrelationIds {
    private CorrelationIds() {}

    public static String normalize(String value) {
        if (value == null || value.isBlank()) return UUID.randomUUID().toString();
        String sanitized = value.replaceAll("[^A-Za-z0-9._:-]", "_");
        return sanitized.substring(0, Math.min(sanitized.length(), 80));
    }
}
