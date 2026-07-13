package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.ticket.TicketNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FailureRecoveryService {
    private final TriageJobRepository jobs;
    private final TriageRequestService requests;
    private final TriageResultRepository results;
    private final ModelInvocationRepository invocations;
    private final Clock clock;

    public FailureRecoveryService(TriageJobRepository jobs, TriageRequestService requests,
            TriageResultRepository results, ModelInvocationRepository invocations, Clock clock) {
        this.jobs = jobs;
        this.requests = requests;
        this.results = results;
        this.invocations = invocations;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<TriageJobSnapshot> failures(UUID workspaceId, int page, int size) {
        PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return jobs.findRecoverableFailures(workspaceId, request)
                .map(TriageJob::snapshot);
    }

    @Transactional(readOnly = true)
    public OperationsOverview overview(UUID workspaceId) {
        List<TriageJob> storedJobs = jobs.findByWorkspaceId(workspaceId);
        List<TriageResult> storedResults = results.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId);
        List<UUID> ticketIds = storedJobs.stream().map(TriageJob::getTicketId).distinct().toList();
        List<ModelInvocation> storedInvocations = ticketIds.isEmpty() ? List.of() : invocations.findByTicketIds(ticketIds);
        Map<UUID, TriageResult> resultByEvent = storedResults.stream()
                .collect(java.util.stream.Collectors.toMap(TriageResult::getEventId, result -> result, (left, right) ->
                        left.getCreatedAt().isAfter(right.getCreatedAt()) ? left : right));
        Map<UUID, ModelInvocation> latestInvocationByTicket = latestInvocations(storedInvocations);

        long fallbackCount = storedResults.stream().filter(result -> result.getDecisionSource() == DecisionSource.RULES_FALLBACK).count();
        long providerFailureCount = storedInvocations.stream().filter(invocation -> !"SUCCESS".equals(invocation.getStatus())).count();
        OperationsOverview.QueueCounts queue = new OperationsOverview.QueueCounts(
                count(storedJobs, TriageJobStatus.QUEUED),
                count(storedJobs, TriageJobStatus.PROCESSING),
                count(storedJobs, TriageJobStatus.COMPLETED),
                count(storedJobs, TriageJobStatus.RETRYABLE_FAILURE),
                count(storedJobs, TriageJobStatus.TERMINAL_FAILURE));

        List<OperationsOverview.RecentOperation> recent = storedJobs.stream()
                .sorted(Comparator.comparing(TriageJob::getUpdatedAt).reversed()
                        .thenComparing(TriageJob::getId, Comparator.reverseOrder()))
                .limit(20)
                .map(job -> operation(job, resultByEvent.get(job.getEventId()), latestInvocationByTicket.get(job.getTicketId())))
                .toList();

        Map<String, ProviderAccumulator> byProvider = new TreeMap<>();
        storedInvocations.forEach(invocation -> byProvider.computeIfAbsent(invocation.getProvider(), ignored -> new ProviderAccumulator())
                .add(invocation));
        List<OperationsOverview.ProviderHealth> providers = byProvider.entrySet().stream()
                .map(entry -> entry.getValue().snapshot(entry.getKey()))
                .toList();
        return new OperationsOverview(clock.instant(), queue, fallbackCount, providerFailureCount, recent, providers);
    }

    private Map<UUID, ModelInvocation> latestInvocations(List<ModelInvocation> storedInvocations) {
        Map<UUID, ModelInvocation> latest = new HashMap<>();
        for (ModelInvocation invocation : storedInvocations) {
            ModelInvocation previous = latest.get(invocation.getTicketId());
            if (previous == null || invocation.getEndedAt().isAfter(previous.getEndedAt())) latest.put(invocation.getTicketId(), invocation);
        }
        return latest;
    }

    private OperationsOverview.RecentOperation operation(TriageJob job, TriageResult result, ModelInvocation invocation) {
        long latency = result == null
                ? durationMillis(job.getStartedAt(), job.getCompletedAt())
                : durationMillis(job.getCreatedAt(), result.getCreatedAt());
        return new OperationsOverview.RecentOperation(job.getId(), job.getTicketId(), job.getStatus(), job.getAttemptCount(),
                job.getCreatedAt(), job.getUpdatedAt(), job.getCompletedAt(), job.getLastErrorCode(),
                invocation == null ? null : invocation.getProvider(),
                invocation == null ? null : invocation.getModelVersion(),
                result == null ? null : result.getDecisionSource().name(), latency);
    }

    private long durationMillis(Instant start, Instant end) {
        return start == null || end == null ? 0 : Math.max(0, Duration.between(start, end).toMillis());
    }

    private long count(List<TriageJob> storedJobs, TriageJobStatus status) {
        return storedJobs.stream().filter(job -> job.getStatus() == status).count();
    }

    private static final class ProviderAccumulator {
        private long requests;
        private long successes;
        private long failures;
        private long inputTokens;
        private long outputTokens;
        private long latency;

        private void add(ModelInvocation invocation) {
            requests++;
            if ("SUCCESS".equals(invocation.getStatus())) successes++; else failures++;
            inputTokens += invocation.getInputTokens() == null ? 0 : invocation.getInputTokens();
            outputTokens += invocation.getOutputTokens() == null ? 0 : invocation.getOutputTokens();
            latency += invocation.getLatencyMs();
        }

        private OperationsOverview.ProviderHealth snapshot(String provider) {
            return new OperationsOverview.ProviderHealth(provider, requests, successes, failures, inputTokens, outputTokens,
                    requests == 0 ? 0 : latency / requests);
        }
    }

    @Transactional
    public TriageJobSnapshot retry(UUID workspaceId, UUID jobId, String correlationId) {
        TriageJob job = jobs.findByIdAndWorkspaceId(jobId, workspaceId)
                .orElseThrow(TicketNotFoundException::new);
        if (job.getStatus() == TriageJobStatus.COMPLETED) {
            throw new IllegalArgumentException("Completed triage jobs cannot be retried");
        }
        if (job.getStatus() != TriageJobStatus.RETRYABLE_FAILURE
                && job.getStatus() != TriageJobStatus.TERMINAL_FAILURE) {
            throw new IllegalArgumentException("Only failed triage jobs can be retried");
        }
        if (job.getStatus() == TriageJobStatus.RETRYABLE_FAILURE
                && !"SYNTHETIC_PROVIDER_TIMEOUT".equals(job.getLastErrorCode())) {
            throw new IllegalArgumentException("Retryable jobs remain owned by SQS");
        }
        return requests.request(workspaceId, job.getTicketId(), correlationId);
    }
}
