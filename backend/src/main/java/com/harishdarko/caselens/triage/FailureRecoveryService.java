package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.ticket.TicketNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
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
    /**
     * How long SQS can keep redelivering a retryable job's message: maxReceiveCount 5 x 90 s visibility timeout is
     * 7.5 minutes, plus margin. A RETRYABLE_FAILURE job not updated for this long has had its message moved to the
     * DLQ, so a reviewer may replay it. If an old message still turns up later, the worker sees the replacement and
     * acknowledges it as a duplicate (TriageWorker.claim), and the active-job unique index blocks a reclaim anyway.
     * The same window marks a QUEUED or PROCESSING job as stranded (see stranded): its message is in the DLQ.
     */
    static final Duration SQS_REDELIVERY_WINDOW = Duration.ofMinutes(10);
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
        return jobs.findRecoverableFailures(workspaceId, clock.instant().minus(SQS_REDELIVERY_WINDOW), request)
                .map(TriageJob::snapshot);
    }

    @Transactional(readOnly = true)
    public OperationsOverview overview(UUID workspaceId) {
        List<TriageJob> storedJobs = jobs.findByWorkspaceId(workspaceId);
        // Column reads, not entities: this runs on every 5 s Operations poll (CL-20).
        List<ResultFacts> storedResults = results.findFactsByWorkspaceId(workspaceId);
        List<UUID> ticketIds = storedJobs.stream().map(TriageJob::getTicketId).distinct().toList();
        List<ModelInvocation> storedInvocations = ticketIds.isEmpty() ? List.of() : invocations.findByTicketIds(ticketIds);
        Map<UUID, ResultFacts> resultByEvent = storedResults.stream()
                .collect(java.util.stream.Collectors.toMap(ResultFacts::eventId, result -> result, (left, right) ->
                        left.createdAt().isAfter(right.createdAt()) ? left : right));
        JobInvocations jobInvocations = new JobInvocations(storedInvocations);

        long fallbackCount = storedResults.stream().filter(result -> result.decisionSource() == DecisionSource.RULES_FALLBACK).count();
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
                .map(job -> operation(job, resultByEvent.get(job.getEventId()), jobInvocations.latestFor(job)))
                .toList();

        Map<String, ProviderAccumulator> byProvider = new TreeMap<>();
        storedInvocations.forEach(invocation -> byProvider.computeIfAbsent(invocation.getProvider(), ignored -> new ProviderAccumulator())
                .add(invocation));
        List<OperationsOverview.ProviderHealth> providers = byProvider.entrySet().stream()
                .map(entry -> entry.getValue().snapshot(entry.getKey()))
                .toList();
        return new OperationsOverview(clock.instant(), queue, fallbackCount, providerFailureCount, recent, providers);
    }

    /**
     * Latency is null until the job has finished: a queued, processing or failed job has no end to measure to, and
     * 0 ms would read as an instant answer. Provider and model are null when no provider call was recorded for this
     * job (for example the synthetic demo failure, which never calls one).
     */
    private OperationsOverview.RecentOperation operation(TriageJob job, ResultFacts result, ModelInvocation invocation) {
        Long latency = result != null
                ? durationMillis(job.getCreatedAt(), result.createdAt())
                : durationMillis(job.getStartedAt(), job.getCompletedAt());
        return new OperationsOverview.RecentOperation(job.getId(), job.getTicketId(), job.getStatus(), job.getAttemptCount(),
                job.getCreatedAt(), job.getUpdatedAt(), job.getCompletedAt(), job.getLastErrorCode(),
                invocation == null ? null : invocation.getProvider(),
                invocation == null ? null : invocation.getModelVersion(),
                result == null ? null : result.decisionSource().name(), latency);
    }

    private Long durationMillis(Instant start, Instant end) {
        return start == null || end == null ? null : Math.max(0, Duration.between(start, end).toMillis());
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

    /**
     * Reviewer recovery. Only the job's ticket id is read here: it is a column read, so the job does not enter this
     * transaction's persistence context before the replay holds the ticket lock. The replay then reads the job and
     * checks it with requireReplayable under that lock (CL3-01). A job loaded before the lock would be served from
     * the persistence context afterwards, and could miss a claim or completion that committed while this waited.
     */
    @Transactional
    public TriageJobSnapshot retry(UUID workspaceId, UUID jobId, String correlationId) {
        UUID ticketId = jobs.findTicketIdByIdAndWorkspaceId(jobId, workspaceId)
                .orElseThrow(TicketNotFoundException::new);
        return requests.replay(workspaceId, ticketId, jobId, correlationId);
    }

    /**
     * Throws unless a reviewer may replay this job now: a terminal failure, the acknowledged synthetic demo failure,
     * a retryable failure SQS stopped redelivering, or a stranded QUEUED or PROCESSING job. TriageRequestService.replay
     * calls it with the ticket lock held, so the job cannot change until the replay commits.
     */
    static void requireReplayable(TriageJob job, Instant now) {
        if (job.getStatus() == TriageJobStatus.COMPLETED) {
            throw new IllegalArgumentException("Completed triage jobs cannot be retried");
        }
        if (job.getStatus() == TriageJobStatus.QUEUED || job.getStatus() == TriageJobStatus.PROCESSING) {
            // A stranded job: its message went to the DLQ (see TriageJobRepository.findRecoverableFailures). The
            // replay closes it under the ticket lock and queues one replacement.
            if (!stranded(job, now)) throw new IllegalArgumentException("Active triage jobs remain owned by SQS");
            return;
        }
        if (job.getStatus() != TriageJobStatus.RETRYABLE_FAILURE
                && job.getStatus() != TriageJobStatus.TERMINAL_FAILURE) {
            throw new IllegalArgumentException("Only failed triage jobs can be retried");
        }
        if (job.getStatus() == TriageJobStatus.RETRYABLE_FAILURE
                && !"SYNTHETIC_PROVIDER_TIMEOUT".equals(job.getLastErrorCode())
                && job.getUpdatedAt().isAfter(now.minus(SQS_REDELIVERY_WINDOW))) {
            throw new IllegalArgumentException("Retryable jobs remain owned by SQS");
        }
    }

    /**
     * A QUEUED or PROCESSING job not updated for the whole redelivery window. Every claim and every final write
     * updates the job, and a PROCESSING job's lease (TriageJobRepository.CLAIM_LEASE) is far shorter than the window,
     * so no worker holds it and SQS has no delivery of its message left.
     */
    static boolean stranded(TriageJob job, Instant now) {
        boolean active = job.getStatus() == TriageJobStatus.QUEUED || job.getStatus() == TriageJobStatus.PROCESSING;
        if (!active || !job.getUpdatedAt().isBefore(now.minus(SQS_REDELIVERY_WINDOW))) return false;
        return job.getStatus() == TriageJobStatus.QUEUED || job.getStartedAt() == null
                || job.getStartedAt().isBefore(now.minus(TriageJobRepository.CLAIM_LEASE));
    }
}
