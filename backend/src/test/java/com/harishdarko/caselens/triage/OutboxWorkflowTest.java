package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketChannel;
import com.harishdarko.caselens.ticket.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxWorkflowTest {
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-11T14:00:00Z"), ZoneOffset.UTC);

    @Mock TicketRepository tickets;
    @Mock OutboxEventRepository events;
    @Mock TriageJobRepository jobs;
    @Mock OutboxPublisher publisher;

    @Test
    void createsIdentifierOnlyPayloadAndLeavesItUnpublishedWhenImmediatePublishFails() {
        Ticket ticket = Ticket.create(ticketId, workspaceId, "Payment issue",
                "Payment was captured but the session never started for this ticket.", TicketChannel.WEB, null, clock.instant());
        when(tickets.findByIdAndWorkspaceIdForUpdate(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        when(events.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobs.save(any(TriageJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new QueuePublishException("QUEUE_TRANSIENT")).when(publisher).publish(any(OutboxEvent.class));

        TriageRequestService service = new TriageRequestService(tickets, events, jobs, publisher, clock);
        TriageJobSnapshot snapshot = service.request(workspaceId, ticketId, "corr-123");

        assertThat(snapshot.status()).isEqualTo(TriageJobStatus.QUEUED);
        assertThat(snapshot.jobId()).isNotNull();
        var event = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(events, org.mockito.Mockito.times(2)).save(event.capture());
        assertThat(event.getAllValues().get(0).getPayload()).contains("\"eventId\"")
                .contains(workspaceId.toString()).contains(ticketId.toString())
                .doesNotContain(ticket.getSubject()).doesNotContain(ticket.getMessage());
        assertThat(event.getAllValues().get(0).getPublishedAt()).isNull();
    }

    @Test
    void returnsExistingJobWithoutPublishingADuplicateRequest() {
        Ticket ticket = Ticket.create(ticketId, workspaceId, "Payment issue",
                "Payment was captured but the session never started for this ticket.", TicketChannel.WEB, null, clock.instant());
        TriageJob existing = TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), workspaceId, ticketId, 1, clock.instant());
        when(tickets.findByIdAndWorkspaceIdForUpdate(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        when(jobs.findFirstByTicketIdAndWorkspaceIdAndContentVersionOrderByCreatedAtDesc(ticketId, workspaceId, 1))
                .thenReturn(Optional.of(existing));

        TriageJobSnapshot snapshot = new TriageRequestService(tickets, events, jobs, publisher, clock)
                .request(workspaceId, ticketId, "corr-duplicate");

        assertThat(snapshot.jobId()).isEqualTo(existing.getId());
        verify(events, never()).save(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void claimsAJobOnlyOnceForDuplicateDelivery() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), workspaceId, ticketId, 1, clock.instant());

        assertThat(job.claim(clock.instant())).isTrue();
        assertThat(job.claim(clock.instant())).isFalse();
        assertThat(job.getStatus()).isEqualTo(TriageJobStatus.PROCESSING);
    }
}
