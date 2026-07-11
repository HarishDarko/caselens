package com.harishdarko.caselens.triage;

import java.util.Objects;

public record ProviderCall(ProviderTriageResult result, Long inputTokens, Long outputTokens) {
    public ProviderCall {
        Objects.requireNonNull(result, "Provider result is required");
        if (inputTokens != null && inputTokens < 0) throw new IllegalArgumentException("Input token count cannot be negative");
        if (outputTokens != null && outputTokens < 0) throw new IllegalArgumentException("Output token count cannot be negative");
    }
}
