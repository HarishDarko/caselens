package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.harishdarko.caselens.ticket.TicketNotFoundException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FailureRecoveryServiceTest {
    @Mock TriageJobRepository jobs;
    @Mock TriageRequestService requests;
    @Mock TriageResultRepository results;
    @Mock ModelInvocationRepository invocations;

    @Test
    void rejectsCrossWorkspaceAndCompletedRecoveryAttempts() {
        UUID jobId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        TriageJob completed = TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), workspaceId, UUID.randomUUID(), 1, Instant.now());
        completed.claim(Instant.now());
        completed.complete(Instant.now());
        when(jobs.findByIdAndWorkspaceId(any(), any())).thenAnswer(invocation -> {
            UUID requestedWorkspace = invocation.getArgument(1);
            return workspaceId.equals(requestedWorkspace) ? Optional.of(completed) : Optional.empty();
        });

        FailureRecoveryService service = new FailureRecoveryService(jobs, requests, results, invocations, java.time.Clock.systemUTC());

        assertThatThrownBy(() -> service.retry(UUID.randomUUID(), jobId, "corr"))
                .isInstanceOf(TicketNotFoundException.class);
        assertThatThrownBy(() -> service.retry(workspaceId, jobId, "corr"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Completed triage jobs cannot be retried");
    }

    @Test
    void rejectsAutomaticRetryStateButAllowsTheAcknowledgedSyntheticRecovery() {
        UUID workspaceId = UUID.randomUUID();
        TriageJob automatic = failedJob(workspaceId, "PROVIDER_TIMEOUT");
        TriageJob synthetic = failedJob(workspaceId, "SYNTHETIC_PROVIDER_TIMEOUT");
        when(jobs.findByIdAndWorkspaceId(automatic.getId(), workspaceId)).thenReturn(Optional.of(automatic));
        when(jobs.findByIdAndWorkspaceId(synthetic.getId(), workspaceId)).thenReturn(Optional.of(synthetic));
        when(requests.replay(workspaceId, synthetic.getTicketId(), synthetic.getId(), "corr")).thenReturn(synthetic.snapshot());
        FailureRecoveryService service = new FailureRecoveryService(jobs, requests, results, invocations,
                java.time.Clock.systemUTC());

        assertThatThrownBy(() -> service.retry(workspaceId, automatic.getId(), "corr"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Retryable jobs remain owned by SQS");

        service.retry(workspaceId, synthetic.getId(), "corr");
        // Recovery uses the replay path; the triage button's request() would return the failed job unchanged.
        verify(requests).replay(workspaceId, synthetic.getTicketId(), synthetic.getId(), "corr");
        verify(requests, never()).request(any(), any(), any());
        verify(requests, never()).replay(any(), any(), org.mockito.ArgumentMatchers.eq(automatic.getId()), any());
    }

    @Test
    void replaysATerminalFailureThroughTheReplayPath() {
        UUID workspaceId = UUID.randomUUID();
        TriageJob terminal = TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), workspaceId, UUID.randomUUID(), 1,
                Instant.now());
        terminal.claim(Instant.now());
        terminal.terminalFailure("INVALID_EVENT", Instant.now());
        when(jobs.findByIdAndWorkspaceId(terminal.getId(), workspaceId)).thenReturn(Optional.of(terminal));
        when(requests.replay(workspaceId, terminal.getTicketId(), terminal.getId(), "corr")).thenReturn(terminal.snapshot());

        new FailureRecoveryService(jobs, requests, results, invocations, java.time.Clock.systemUTC())
                .retry(workspaceId, terminal.getId(), "corr");

        verify(requests).replay(workspaceId, terminal.getTicketId(), terminal.getId(), "corr");
        verify(requests, never()).request(any(), any(), any());
    }

    @Test
    void replaysARetryableFailureOnceSqsHasGivenUpOnIt() {
        UUID workspaceId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-22T12:00:00Z");
        TriageJob recent = TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), workspaceId, UUID.randomUUID(), 1,
                now.minusSeconds(900));
        recent.claim(now.minusSeconds(120));
        recent.retryableFailure("PROVIDER_TIMEOUT", now.minusSeconds(60));
        TriageJob abandoned = TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), workspaceId, UUID.randomUUID(), 1,
                now.minusSeconds(1800));
        abandoned.claim(now.minusSeconds(700));
        abandoned.retryableFailure("PROVIDER_TIMEOUT", now.minus(FailureRecoveryService.SQS_REDELIVERY_WINDOW).minusSeconds(1));
        when(jobs.findByIdAndWorkspaceId(recent.getId(), workspaceId)).thenReturn(Optional.of(recent));
        when(jobs.findByIdAndWorkspaceId(abandoned.getId(), workspaceId)).thenReturn(Optional.of(abandoned));
        when(requests.replay(workspaceId, abandoned.getTicketId(), abandoned.getId(), "corr")).thenReturn(abandoned.snapshot());
        FailureRecoveryService service = new FailureRecoveryService(jobs, requests, results, invocations,
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));

        assertThatThrownBy(() -> service.retry(workspaceId, recent.getId(), "corr"))
                .hasMessage("Retryable jobs remain owned by SQS");
        service.retry(workspaceId, abandoned.getId(), "corr");

        verify(requests).replay(workspaceId, abandoned.getTicketId(), abandoned.getId(), "corr");
    }

    private static TriageJob failedJob(UUID workspaceId, String errorCode) {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), workspaceId, UUID.randomUUID(), 1,
                Instant.now());
        job.claim(Instant.now());
        job.retryableFailure(errorCode, Instant.now());
        return job;
    }
}
