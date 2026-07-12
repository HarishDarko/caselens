package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationService {
    private final TriageResultRepository results;
    private final TriageJobRepository jobs;
    private final TicketRepository tickets;
    private final TriageFeedbackRepository feedback;
    private final EvaluationGroundTruthRepository groundTruth;
    private final ModelInvocationRepository invocations;
    private final Clock clock;

    EvaluationService(TriageResultRepository results, TriageJobRepository jobs, TicketRepository tickets,
            TriageFeedbackRepository feedback, EvaluationGroundTruthRepository groundTruth,
            ModelInvocationRepository invocations, Clock clock) {
        this.results = results;
        this.jobs = jobs;
        this.tickets = tickets;
        this.feedback = feedback;
        this.groundTruth = groundTruth;
        this.invocations = invocations;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public EvaluationSummary summarize(UUID workspaceId) {
        List<TriageResult> storedResults = results.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId);
        List<TriageJob> storedJobs = jobs.findByWorkspaceId(workspaceId);
        Map<UUID, TriageJob> jobsByEvent = storedJobs.stream().collect(java.util.stream.Collectors.toMap(
                TriageJob::getEventId, job -> job, (left, right) -> right));
        Map<UUID, TriageFeedback> latestFeedback = latestFeedback(workspaceId);
        Map<String, EvaluationGroundTruth> expected = groundTruth.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(EvaluationGroundTruth::getScenarioKey, value -> value));

        int evaluated = 0;
        int categoryMatches = 0;
        int urgencyMatches = 0;
        int fullMatches = 0;
        int corrections = 0;
        List<Long> latencies = new ArrayList<>();
        List<UUID> ticketIds = new ArrayList<>();
        Map<UUID, Ticket> scopedTickets = new HashMap<>();
        for (TriageResult result : storedResults) {
            ticketIds.add(result.getTicketId());
            tickets.findByIdAndWorkspaceId(result.getTicketId(), workspaceId).ifPresent(ticket -> scopedTickets.put(ticket.getId(), ticket));
            TriageFeedback correction = latestFeedback.get(result.getId());
            if (correction != null && correction.differsFrom(result)) corrections++;
            TriageJob job = jobsByEvent.get(result.getEventId());
            if (job != null) {
                latencies.add(Math.max(0, Duration.between(job.getCreatedAt(), result.getCreatedAt()).toMillis()));
            }
            Ticket ticket = scopedTickets.get(result.getTicketId());
            EvaluationGroundTruth truth = ticket == null ? null : expected.get(ticket.getScenarioKey());
            if (truth == null) continue;
            evaluated++;
            boolean categoryMatch = result.getCategory() == truth.getExpectedCategory();
            boolean urgencyMatch = result.getUrgency() == truth.getExpectedUrgency();
            if (categoryMatch) categoryMatches++;
            if (urgencyMatch) urgencyMatches++;
            if (categoryMatch && urgencyMatch) fullMatches++;
        }

        List<ModelInvocation> storedInvocations = ticketIds.isEmpty() ? List.of() : invocations.findByTicketIds(ticketIds);
        Map<JobKey, TriageJob> latestJobs = latestJobs(storedJobs);
        return new EvaluationSummary(clock.instant(), storedResults.size(), evaluated,
                ratio(categoryMatches, evaluated), ratio(urgencyMatches, evaluated), ratio(fullMatches, evaluated),
                ratio(corrections, storedResults.size()), percentile(latencies, 0.50), percentile(latencies, 0.95),
                ratio((int) latestJobs.values().stream().filter(this::isFailure).count(),
                        (int) latestJobs.values().stream().filter(this::isFinal).count()), usage(storedInvocations));
    }

    private Map<JobKey, TriageJob> latestJobs(List<TriageJob> storedJobs) {
        Map<JobKey, TriageJob> latest = new HashMap<>();
        for (TriageJob job : storedJobs) {
            JobKey key = new JobKey(job.getTicketId(), job.getContentVersion());
            TriageJob prior = latest.get(key);
            if (prior == null || job.getCreatedAt().isAfter(prior.getCreatedAt())
                    || (job.getCreatedAt().equals(prior.getCreatedAt()) && job.getId().compareTo(prior.getId()) > 0)) {
                latest.put(key, job);
            }
        }
        return latest;
    }

    private Map<UUID, TriageFeedback> latestFeedback(UUID workspaceId) {
        Map<UUID, TriageFeedback> latest = new HashMap<>();
        for (TriageFeedback item : feedback.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)) {
            TriageFeedback prior = latest.get(item.getOriginalResultId());
            if (prior == null || item.getCreatedAt().isAfter(prior.getCreatedAt())
                    || (item.getCreatedAt().equals(prior.getCreatedAt()) && item.getId().compareTo(prior.getId()) > 0)) {
                latest.put(item.getOriginalResultId(), item);
            }
        }
        return latest;
    }

    private boolean isFailure(TriageJob job) {
        return job.getStatus() == TriageJobStatus.TERMINAL_FAILURE;
    }

    private boolean isFinal(TriageJob job) {
        return job.getStatus() == TriageJobStatus.COMPLETED || job.getStatus() == TriageJobStatus.TERMINAL_FAILURE;
    }

    private List<ProviderUsageSummary> usage(List<ModelInvocation> storedInvocations) {
        Map<String, UsageAccumulator> byProvider = new TreeMap<>();
        for (ModelInvocation invocation : storedInvocations) {
            UsageAccumulator usage = byProvider.computeIfAbsent(invocation.getProvider(), ignored -> new UsageAccumulator());
            usage.requests++;
            if ("SUCCESS".equals(invocation.getStatus())) usage.successes++; else usage.failures++;
            usage.inputTokens += invocation.getInputTokens() == null ? 0 : invocation.getInputTokens();
            usage.outputTokens += invocation.getOutputTokens() == null ? 0 : invocation.getOutputTokens();
        }
        return byProvider.entrySet().stream()
                .map(entry -> new ProviderUsageSummary(entry.getKey(), entry.getValue().requests, entry.getValue().successes,
                        entry.getValue().failures, entry.getValue().inputTokens, entry.getValue().outputTokens)).toList();
    }

    private long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1));
        return sorted.get(index);
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static final class UsageAccumulator {
        private long requests;
        private long successes;
        private long failures;
        private long inputTokens;
        private long outputTokens;
    }

    private record JobKey(UUID ticketId, int contentVersion) {}
}
