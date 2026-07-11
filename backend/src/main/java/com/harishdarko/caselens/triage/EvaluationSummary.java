package com.harishdarko.caselens.triage;

import java.time.Instant;
import java.util.List;

public record EvaluationSummary(Instant generatedAt, int triageResults, int evaluatedResults,
        double categoryAgreementRate, double urgencyAgreementRate, double agreementRate,
        double correctionRate, long medianLatencyMs, long p95LatencyMs, double failureRate,
        List<ProviderUsageSummary> providerUsage) {
    public EvaluationSummary {
        providerUsage = List.copyOf(providerUsage);
    }
}
