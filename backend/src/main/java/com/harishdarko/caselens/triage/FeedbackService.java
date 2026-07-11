package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketNotFoundException;
import com.harishdarko.caselens.ticket.TicketRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {
    private final TicketRepository tickets;
    private final TriageResultRepository results;
    private final TriageFeedbackRepository feedback;
    private final Clock clock;

    FeedbackService(TicketRepository tickets, TriageResultRepository results, TriageFeedbackRepository feedback, Clock clock) {
        this.tickets = tickets;
        this.results = results;
        this.feedback = feedback;
        this.clock = clock;
    }

    @Transactional
    public FeedbackSnapshot submit(UUID workspaceId, UUID ticketId, UUID requestedResultId,
            Category correctedCategory, Urgency correctedUrgency, SlaRisk correctedSlaRisk, String note) {
        Ticket ticket = scopedTicket(workspaceId, ticketId);
        TriageResult result = requestedResultId == null
                ? results.findFirstByTicketIdAndWorkspaceIdOrderByCreatedAtDesc(ticketId, workspaceId)
                        .orElseThrow(() -> new IllegalArgumentException("A completed triage result is required"))
                : results.findByIdAndWorkspaceId(requestedResultId, workspaceId)
                        .orElseThrow(() -> new IllegalArgumentException("Triage result not found"));
        if (!result.getTicketId().equals(ticket.getId())) {
            throw new IllegalArgumentException("Triage result does not belong to the ticket");
        }
        TriageFeedback candidate = TriageFeedback.submit(UUID.randomUUID(), workspaceId, ticketId, result.getId(),
                correctedCategory, correctedUrgency, correctedSlaRisk, note, "demo-reviewer", clock.instant());
        if (!candidate.differsFrom(result)) {
            throw new IllegalArgumentException("Feedback must change at least one triage field");
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
