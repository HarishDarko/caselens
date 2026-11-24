package com.harishdarko.caselens.triage;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The newest triage result of each ticket, with the reviewer's latest call on it, for the ticket list and the seeded
 * session. Everything is read for the whole page of tickets at once, so the number of statements does not grow with
 * the number of tickets: one for the newest results, one for their reviews, and (only when a result is a rules
 * fallback) one for its job and one for the model calls that say why.
 */
@Service
public class LatestTriageReader {
    private final TriageResultRepository results;
    private final TriageFeedbackRepository feedback;
    private final TriageJobRepository jobs;
    private final ModelInvocationRepository invocations;
    private final PackResolver packs;
    private final ObjectMapper mapper;

    LatestTriageReader(TriageResultRepository results, TriageFeedbackRepository feedback, TriageJobRepository jobs,
            ModelInvocationRepository invocations, PackResolver packs, ObjectMapper mapper) {
        this.results = results;
        this.feedback = feedback;
        this.jobs = jobs;
        this.invocations = invocations;
        this.packs = packs;
        this.mapper = mapper;
    }

    /** The reviewer's latest call on a result: accepted as it is, or corrected (urgency and note as submitted). */
    public record LatestFeedback(FeedbackDecision decision, Urgency urgency, String note, Instant createdAt) {}

    /** The newest result of a ticket and the latest review of that result (null when none). */
    public record Latest(TriageResultView result, LatestFeedback feedback) {}

    /** Only tickets that have a result are in the map. A review belongs to a result: a newer live result starts unreviewed. */
    @Transactional(readOnly = true)
    public Map<UUID, Latest> forTickets(UUID workspaceId, Collection<UUID> ticketIds) {
        if (ticketIds.isEmpty()) return Map.of();
        List<TriageResult> latest = results.findLatestByTicketIds(workspaceId, ticketIds);
        if (latest.isEmpty()) return Map.of();
        PackRules pack = packs.forWorkspace(workspaceId);
        Map<UUID, FallbackReason> reasons = fallbackReasons(workspaceId, latest);
        Map<UUID, TriageFeedback> reviews = latestReviews(workspaceId, latest);
        Map<UUID, Latest> byTicket = new HashMap<>();
        for (TriageResult result : latest) {
            TriageFeedback review = reviews.get(result.getId());
            byTicket.put(result.getTicketId(), new Latest(
                    TriageResultView.from(result, reasons.get(result.getId()), pack, mapper),
                    review == null ? null : new LatestFeedback(review.getDecision(), review.getCorrectedUrgency(),
                            review.getNote(), review.getCreatedAt())));
        }
        return byTicket;
    }

    private Map<UUID, TriageFeedback> latestReviews(UUID workspaceId, List<TriageResult> latest) {
        List<UUID> resultIds = latest.stream().map(TriageResult::getId).toList();
        Map<UUID, TriageFeedback> newest = new HashMap<>();
        // Oldest first, so the last one put for a result is its newest review.
        for (TriageFeedback item : feedback.findByWorkspaceIdAndOriginalResultIdInOrderByCreatedAtAscIdAsc(workspaceId, resultIds)) {
            newest.put(item.getOriginalResultId(), item);
        }
        return newest;
    }

    /** Why each rules-fallback result fell back, read from its job's failed model call; empty when none fell back. */
    private Map<UUID, FallbackReason> fallbackReasons(UUID workspaceId, List<TriageResult> latest) {
        List<TriageResult> fallbacks = latest.stream()
                .filter(result -> result.getDecisionSource() == DecisionSource.RULES_FALLBACK && result.getEventId() != null)
                .toList();
        if (fallbacks.isEmpty()) return Map.of();
        Map<UUID, TriageJob> jobsByEvent = jobs.findByWorkspaceIdAndEventIdIn(workspaceId,
                fallbacks.stream().map(TriageResult::getEventId).toList()).stream()
                .collect(Collectors.toMap(TriageJob::getEventId, job -> job, (left, right) -> right));
        List<UUID> ticketIds = fallbacks.stream().map(TriageResult::getTicketId).distinct().toList();
        JobInvocations calls = new JobInvocations(invocations.findByTicketIds(ticketIds));
        Map<UUID, FallbackReason> reasons = new HashMap<>();
        for (TriageResult result : fallbacks) {
            TriageJob job = jobsByEvent.get(result.getEventId());
            reasons.put(result.getId(), job == null ? FallbackReason.PROVIDER_UNAVAILABLE
                    : FallbackReason.fromInvocation(calls.latestFor(job)));
        }
        return reasons;
    }
}
