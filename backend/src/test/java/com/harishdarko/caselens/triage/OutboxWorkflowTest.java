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
    void returnsARetryableFailureThatSqsStillOwnsInsteadOfQueuingASecondJob() {
        Ticket ticket = ticket();
        TriageJob retryable = failed(TriageJobStatus.RETRYABLE_FAILURE, "PROVIDER_TIMEOUT");
        when(tickets.findByIdAndWorkspaceIdForUpdate(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        when(jobs.findFirstByTicketIdAndWorkspaceIdAndContentVersionOrderByCreatedAtDesc(ticketId, workspaceId, 1))
                .thenReturn(Optional.of(retryable));

        TriageJobSnapshot snapshot = new TriageRequestService(tickets, events, jobs, publisher, clock)
                .request(workspaceId, ticketId, "corr-click-again");

        assertThat(snapshot.jobId()).isEqualTo(retryable.getId());
        assertThat(snapshot.status()).isEqualTo(TriageJobStatus.RETRYABLE_FAILURE);
        verify(events, never()).save(any());
        verify(jobs, never()).save(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void theTriageButtonStillQueuesANewJobAfterATerminalFailure() {
        Ticket ticket = ticket();
        TriageJob terminal = failed(TriageJobStatus.TERMINAL_FAILURE, "INVALID_EVENT");
        when(tickets.findByIdAndWorkspaceIdForUpdate(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        when(jobs.findFirstByTicketIdAndWorkspaceIdAndContentVersionOrderByCreatedAtDesc(ticketId, workspaceId, 1))
                .thenReturn(Optional.of(terminal));
        when(events.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobs.save(any(TriageJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TriageJobSnapshot snapshot = new TriageRequestService(tickets, events, jobs, publisher, clock)
                .request(workspaceId, ticketId, "corr-after-terminal");

        assertThat(snapshot.jobId()).isNotEqualTo(terminal.getId());
        assertThat(snapshot.status()).isEqualTo(TriageJobStatus.QUEUED);
        var saved = org.mockito.ArgumentCaptor.forClass(TriageJob.class);
        verify(jobs).save(saved.capture());
        assertThat(saved.getValue().getReplayedFromJobId()).isEqualTo(terminal.getId());
    }

    @Test
    void replayQueuesAReplacementForAnAcknowledgedRetryableFailure() {
        Ticket ticket = ticket();
        TriageJob synthetic = failed(TriageJobStatus.RETRYABLE_FAILURE, "SYNTHETIC_PROVIDER_TIMEOUT");
        when(tickets.findByIdAndWorkspaceIdForUpdate(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        when(jobs.findFirstByTicketIdAndWorkspaceIdAndContentVersionOrderByCreatedAtDesc(ticketId, workspaceId, 1))
                .thenReturn(Optional.of(synthetic));
        when(events.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobs.save(any(TriageJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TriageJobSnapshot snapshot = new TriageRequestService(tickets, events, jobs, publisher, clock)
                .replay(workspaceId, ticketId, synthetic.getId(), "corr-recovery");

        assertThat(snapshot.jobId()).isNotEqualTo(synthetic.getId());
        assertThat(snapshot.status()).isEqualTo(TriageJobStatus.QUEUED);
        var saved = org.mockito.ArgumentCaptor.forClass(TriageJob.class);
        verify(jobs).save(saved.capture());
        assertThat(saved.getValue().getReplayedFromJobId()).isEqualTo(synthetic.getId());
        verify(publisher).publish(any(OutboxEvent.class));
    }

    @Test
    void aSecondReplayReturnsTheReplacementInsteadOfQueuingAnother() {
        Ticket ticket = ticket();
        TriageJob terminal = failed(TriageJobStatus.TERMINAL_FAILURE, "INVALID_EVENT");
        TriageJob replacement = TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), workspaceId, ticketId, 1, clock.instant());
        when(tickets.findByIdAndWorkspaceIdForUpdate(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        when(jobs.findFirstByTicketIdAndWorkspaceIdAndContentVersionOrderByCreatedAtDesc(ticketId, workspaceId, 1))
                .thenReturn(Optional.of(replacement));

        TriageJobSnapshot snapshot = new TriageRequestService(tickets, events, jobs, publisher, clock)
                .replay(workspaceId, ticketId, terminal.getId(), "corr-double-click");

        assertThat(snapshot.jobId()).isEqualTo(replacement.getId());
        verify(events, never()).save(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void anOldFailureIsNeverReplayedPastANewerJobThatSqsStillOwns() {
        Ticket ticket = ticket();
        TriageJob oldTerminal = failed(TriageJobStatus.TERMINAL_FAILURE, "INVALID_EVENT");
        TriageJob sqsOwned = failed(TriageJobStatus.RETRYABLE_FAILURE, "PROVIDER_TIMEOUT");
        when(tickets.findByIdAndWorkspaceIdForUpdate(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        when(jobs.findFirstByTicketIdAndWorkspaceIdAndContentVersionOrderByCreatedAtDesc(ticketId, workspaceId, 1))
                .thenReturn(Optional.of(sqsOwned));

        TriageJobSnapshot snapshot = new TriageRequestService(tickets, events, jobs, publisher, clock)
                .replay(workspaceId, ticketId, oldTerminal.getId(), "corr-stale-retry");

        assertThat(snapshot.jobId()).isEqualTo(sqsOwned.getId());
        verify(jobs, never()).save(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void replayClosesAStrandedJobAndQueuesOneReplacement() {
        Ticket ticket = ticket();
        TriageJob stranded = TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), workspaceId, ticketId, 1,
                clock.instant().minusSeconds(1800));
        stranded.claim(clock.instant().minusSeconds(1200));
        when(tickets.findByIdAndWorkspaceIdForUpdate(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        when(jobs.findFirstByTicketIdAndWorkspaceIdAndContentVersionOrderByCreatedAtDesc(ticketId, workspaceId, 1))
                .thenReturn(Optional.of(stranded));
        when(jobs.closeStranded(stranded.getId(), "SQS_DELIVERY_EXHAUSTED", clock.instant(),
                clock.instant().minus(FailureRecoveryService.SQS_REDELIVERY_WINDOW),
                clock.instant().minus(TriageJobRepository.CLAIM_LEASE))).thenReturn(1);
        when(events.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobs.save(any(TriageJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TriageJobSnapshot snapshot = new TriageRequestService(tickets, events, jobs, publisher, clock)
                .replay(workspaceId, ticketId, stranded.getId(), "corr-stranded");

        assertThat(snapshot.jobId()).isNotEqualTo(stranded.getId());
        assertThat(snapshot.status()).isEqualTo(TriageJobStatus.QUEUED);
        var saved = org.mockito.ArgumentCaptor.forClass(TriageJob.class);
        verify(jobs).save(saved.capture());
        assertThat(saved.getValue().getReplayedFromJobId()).isEqualTo(stranded.getId());
    }

    @Test
    void replayLeavesAJobAWorkerClaimedFirstAlone() {
        Ticket ticket = ticket();
        TriageJob queued = TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), workspaceId, ticketId, 1,
                clock.instant().minusSeconds(1800));
        when(tickets.findByIdAndWorkspaceIdForUpdate(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        when(jobs.findFirstByTicketIdAndWorkspaceIdAndContentVersionOrderByCreatedAtDesc(ticketId, workspaceId, 1))
                .thenReturn(Optional.of(queued));
        when(jobs.closeStranded(any(), any(), any(), any(), any())).thenReturn(0);

        TriageJobSnapshot snapshot = new TriageRequestService(tickets, events, jobs, publisher, clock)
                .replay(workspaceId, ticketId, queued.getId(), "corr-race");

        assertThat(snapshot.jobId()).isEqualTo(queued.getId());
        verify(jobs, never()).save(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void aDatabaseErrorAfterCommitDoesNotFailTheRequestForACommittedJob() {
        // CL2-05: the publish succeeded but recording published_at failed. The job is committed, so the caller gets
        // it; the relay reconciles the outbox row later.
        Ticket ticket = ticket();
        when(tickets.findByIdAndWorkspaceIdForUpdate(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        when(events.save(any(OutboxEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("connection lost"));
        when(jobs.save(any(TriageJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TriageJobSnapshot snapshot = new TriageRequestService(tickets, events, jobs, publisher, clock)
                .request(workspaceId, ticketId, "corr-after-commit");

        assertThat(snapshot.status()).isEqualTo(TriageJobStatus.QUEUED);
        verify(publisher).publish(any(OutboxEvent.class));
        verify(events, org.mockito.Mockito.times(2)).save(any(OutboxEvent.class));
    }

    private Ticket ticket() {
        return Ticket.create(ticketId, workspaceId, "Payment issue",
                "Payment was captured but the session never started for this ticket.", TicketChannel.WEB, null, clock.instant());
    }

    private TriageJob failed(TriageJobStatus status, String errorCode) {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), workspaceId, ticketId, 1, clock.instant());
        job.claim(clock.instant());
        if (status == TriageJobStatus.RETRYABLE_FAILURE) job.retryableFailure(errorCode, clock.instant());
        else job.terminalFailure(errorCode, clock.instant());
        return job;
    }

    @Test
    void claimsAJobOnlyOnceForDuplicateDelivery() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), workspaceId, ticketId, 1, clock.instant());

        assertThat(job.claim(clock.instant())).isTrue();
        assertThat(job.claim(clock.instant())).isFalse();
        assertThat(job.getStatus()).isEqualTo(TriageJobStatus.PROCESSING);
    }
}
