package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.common.WorkspaceLimitExceededException;
import com.harishdarko.caselens.demo.DemoWorkspaceAccess;
import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketNotFoundException;
import com.harishdarko.caselens.ticket.TicketRepository;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {
    private final TicketRepository tickets;
    private final TriageResultRepository results;
    private final TriageFeedbackRepository feedback;
    private final Clock clock;
    private final PackResolver packs;
    private final DemoWorkspaceAccess workspaces;
    private final int maxFeedbackPerWorkspace;

    FeedbackService(TicketRepository tickets, TriageResultRepository results, TriageFeedbackRepository feedback, Clock clock,
            PackResolver packs, DemoWorkspaceAccess workspaces,
            @Value("${caselens.limits.feedback-per-workspace:100}") int maxFeedbackPerWorkspace) {
        this.tickets = tickets;
        this.results = results;
        this.feedback = feedback;
        this.clock = clock;
        this.packs = packs;
        this.workspaces = workspaces;
        this.maxFeedbackPerWorkspace = maxFeedbackPerWorkspace;
    }

    /**
     * Saves a reviewer correction. requestId, when the client sends one, becomes the feedback id, so the same submit
     * arriving twice (the browser resends it after a throttled 429 it could not read, CL-05) saves one entry and
     * answers the second arrival with it.
     */
    @Transactional
    public FeedbackSnapshot submit(UUID workspaceId, UUID ticketId, UUID requestedResultId,
            Category correctedCategory, Urgency correctedUrgency, SlaRisk correctedSlaRisk, String note, UUID requestId) {
        // The workspace lock comes first, as in TicketService, and is held until the insert commits. Concurrent
        // submits then count one after the other; a concurrent reset finishes before the ticket and result are read,
        // so a deleted ticket is a 404 instead of a foreign-key failure on the insert; and a workspace the retention
        // sweep deleted after the token check is a 401 (session ended) instead of a 404 for its ticket (CL3-03).
        workspaces.lockForUpdate(workspaceId);
        if (requestId != null) {
            var earlier = feedback.findByIdAndWorkspaceId(requestId, workspaceId);
            if (earlier.isPresent()) {
                if (!earlier.get().getTicketId().equals(ticketId)) {
                    throw new IllegalArgumentException("The request id belongs to another feedback entry");
                }
                return FeedbackSnapshot.from(earlier.get());
            }
            if (feedback.existsById(requestId)) throw new IllegalArgumentException("The request id is already in use");
        }
        if (correctedCategory != null && !packs.forWorkspace(workspaceId).categories().contains(correctedCategory)) {
            // Same 400 "Validation failed" answer an unknown enum value gave before categories came from packs.
            throw new ConstraintViolationException("Category is not part of this workspace's industry", Set.of());
        }
        Ticket ticket = scopedTicket(workspaceId, ticketId);
        TriageResult result = requestedResultId == null
                ? results.findFirstByTicketIdAndWorkspaceIdOrderByCreatedAtDesc(ticketId, workspaceId)
                        .orElseThrow(() -> new IllegalArgumentException("A completed triage result is required"))
                : results.findByIdAndWorkspaceId(requestedResultId, workspaceId)
                        .orElseThrow(() -> new IllegalArgumentException("Triage result not found"));
        if (!result.getTicketId().equals(ticket.getId())) {
            throw new IllegalArgumentException("Triage result does not belong to the ticket");
        }
        TriageFeedback candidate = TriageFeedback.submit(requestId == null ? UUID.randomUUID() : requestId, workspaceId, ticketId, result.getId(),
                correctedCategory, correctedUrgency, correctedSlaRisk, note, "demo-reviewer", clock.instant());
        if (!candidate.differsFrom(result)) {
            throw new IllegalArgumentException("Feedback must change at least one triage field");
        }
        if (feedback.countByWorkspaceId(workspaceId) >= maxFeedbackPerWorkspace) {
            throw new WorkspaceLimitExceededException("feedback entries", maxFeedbackPerWorkspace);
        }
        TriageFeedback saved = feedback.save(candidate);
        return FeedbackSnapshot.from(saved);
    }

    @Transactional(readOnly = true)
    public List<FeedbackSnapshot> list(UUID workspaceId, UUID ticketId) {
        scopedTicket(workspaceId, ticketId);
        return feedback.findByTicketIdAndWorkspaceIdOrderByCreatedAtAscIdAsc(ticketId, workspaceId).stream()
                .map(FeedbackSnapshot::from).toList();
    }

    private Ticket scopedTicket(UUID workspaceId, UUID ticketId) {
        return tickets.findByIdAndWorkspaceId(ticketId, workspaceId).orElseThrow(TicketNotFoundException::new);
    }
}
