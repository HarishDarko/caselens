package com.harishdarko.caselens.triage;

import java.time.Instant;
import java.util.List;

/**
 * What a workspace's triage has measured. An empty figure is null, never 0: no latency and no rate exist until there
 * is something to measure, and a page showing 0% would claim a measurement that was not made.
 * <ul>
 * <li>{@code triageResults}, the latency percentiles, {@code failureRate}, {@code providerUsage} and
 * {@code recentEvents} are live only: recorded results (captured earlier and seeded with the workspace) are
 * left out of them.</li>
 * <li>{@code recordedResults} counts the recorded results. The benchmark rates ({@code agreementRate} and its two
 * parts, over {@code evaluatedResults}) are measured on recorded and live results alike: both are real model answers.</li>
 * <li>{@code reviewedResults}, {@code acceptedResults}, {@code acceptanceRate} and {@code overrideRate} come from the
 * reviewer's latest call on each result, whether the result is recorded or live.</li>
 * </ul>
 */
public record EvaluationSummary(Instant generatedAt, int triageResults, int evaluatedResults,
        Double categoryAgreementRate, Double urgencyAgreementRate, Double agreementRate,
        Double correctionRate, Long medianLatencyMs, Long p95LatencyMs, Double failureRate,
        List<ProviderUsageSummary> providerUsage, List<EvaluationEvent> recentEvents,
        int reviewedResults, int acceptedResults, Double acceptanceRate, Double overrideRate, int recordedResults) {
    public EvaluationSummary {
        providerUsage = List.copyOf(providerUsage);
        recentEvents = List.copyOf(recentEvents);
    }
}
