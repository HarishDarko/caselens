package com.harishdarko.caselens.triage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Matches model invocations to the triage job they were made for. Invocations carry the job's event id, so a replayed
 * job and the job it replaced each keep their own provider telemetry. Rows written before V8 have no event id; those
 * match a job of the same ticket when the call ran inside the job's lifetime.
 */
final class JobInvocations {
    private final Map<UUID, List<ModelInvocation>> byEvent = new HashMap<>();
    private final Map<UUID, List<ModelInvocation>> legacyByTicket = new HashMap<>();

    JobInvocations(List<ModelInvocation> invocations) {
        for (ModelInvocation invocation : invocations) {
            if (invocation.getEventId() != null) {
                byEvent.computeIfAbsent(invocation.getEventId(), ignored -> new ArrayList<>()).add(invocation);
            } else {
                legacyByTicket.computeIfAbsent(invocation.getTicketId(), ignored -> new ArrayList<>()).add(invocation);
            }
        }
    }

    /** The newest invocation made for this job, or null when no provider call was recorded for it. */
    ModelInvocation latestFor(TriageJob job) {
        ModelInvocation own = latestForEvent(job.getEventId());
        if (own != null) return own;
        Instant from = job.getCreatedAt();
        Instant to = job.getCompletedAt() != null ? job.getCompletedAt() : job.getUpdatedAt();
        ModelInvocation latest = null;
        for (ModelInvocation invocation : legacyByTicket.getOrDefault(job.getTicketId(), List.of())) {
            if (invocation.getStartedAt().isBefore(from) || invocation.getEndedAt().isAfter(to)) continue;
            latest = newer(latest, invocation);
        }
        return latest;
    }

    /** The newest invocation recorded with this event id, or null. */
    ModelInvocation latestForEvent(UUID eventId) {
        ModelInvocation latest = null;
        for (ModelInvocation invocation : byEvent.getOrDefault(eventId, List.of())) latest = newer(latest, invocation);
        return latest;
    }

    private static ModelInvocation newer(ModelInvocation current, ModelInvocation candidate) {
        return current == null || candidate.getEndedAt().isAfter(current.getEndedAt()) ? candidate : current;
    }
}
