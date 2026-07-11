package com.harishdarko.caselens.triage;

import java.util.Objects;

public record ValidatedProviderResult(ProviderTriageResult providerResult, ReliabilitySignal reliabilitySignal) {
    public ValidatedProviderResult {
        Objects.requireNonNull(providerResult, "Provider result is required");
        Objects.requireNonNull(reliabilitySignal, "Reliability signal is required");
    }
}
