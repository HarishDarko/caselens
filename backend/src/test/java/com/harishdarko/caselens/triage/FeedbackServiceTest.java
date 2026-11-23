package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harishdarko.caselens.demo.DemoWorkspaceAccess;
import com.harishdarko.caselens.demo.InvalidDemoTokenException;
import com.harishdarko.caselens.ticket.TicketNotFoundException;
import com.harishdarko.caselens.ticket.TicketRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** CL3-03: the workspace lock comes before any read, as in TicketService. */
@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {
    @Mock TicketRepository tickets;
    @Mock TriageResultRepository results;
    @Mock TriageFeedbackRepository feedback;
    @Mock PackResolver packs;
    @Mock DemoWorkspaceAccess workspaces;

    private FeedbackService service() {
        return new FeedbackService(tickets, results, feedback, Clock.systemUTC(), packs, workspaces, 100);
    }

    @Test
    void feedbackOnADeletedWorkspaceIsAnEndedSessionNotAMissingTicket() {
        UUID workspaceId = UUID.randomUUID();
        doThrow(new InvalidDemoTokenException()).when(workspaces).lockForUpdate(workspaceId);

        assertThatThrownBy(() -> service().submit(workspaceId, UUID.randomUUID(), null,
                Category.of("CHARGING_SESSION"), Urgency.HIGH, null, "note", null))
                .isInstanceOf(InvalidDemoTokenException.class);
        verify(tickets, never()).findByIdAndWorkspaceId(any(), any());
        verify(packs, never()).forWorkspace(any());
    }

    @Test
    void locksTheWorkspaceBeforeItReadsTheTicket() {
        UUID workspaceId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        when(tickets.findByIdAndWorkspaceId(ticketId, workspaceId)).thenReturn(Optional.empty());

        // A ticket that a concurrent reset deleted before this submit got the lock is a clean 404, not a 500.
        assertThatThrownBy(() -> service().submit(workspaceId, ticketId, null, null, Urgency.HIGH, null, "note", null))
                .isInstanceOf(TicketNotFoundException.class);
        InOrder order = inOrder(workspaces, tickets);
        order.verify(workspaces).lockForUpdate(workspaceId);
        order.verify(tickets).findByIdAndWorkspaceId(ticketId, workspaceId);
    }
}
