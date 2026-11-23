package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    @Mock TriageJobRepository jobs;
    @Mock TriageRequestService requests;
    @Mock TriageResultRepository results;
    @Mock ModelInvocationRepository invocations;

    private FailureRecoveryService service() {
        return new FailureRecoveryService(jobs, requests, results, invocations,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    @Test
    void aJobOfAnotherWorkspaceIsNotFound() {
        UUID jobId = UUID.randomUUID();
        when(jobs.findTicketIdByIdAndWorkspaceId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().retry(UUID.randomUUID(), jobId, "corr"))
                .isInstanceOf(TicketNotFoundException.class);
        verify(requests, never()).replay(any(), any(), any(), any());
    }

    /**
     * CL3-01: retry reads only the ticket id and leaves every check on the job to the replay, which makes it under the
     * ticket lock. It never loads the job itself, so the replay cannot be served a copy read before the lock.
     */
    @Test
    void handsTheJobToTheReplayWithoutLoadingIt() {
        UUID workspaceId = UUID.randomUUID();
        TriageJob terminal = TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), workspaceId, UUID.randomUUID(), 1,
                NOW.minusSeconds(60));
        terminal.claim(NOW.minusSeconds(30));
        terminal.terminalFailure("INVALID_EVENT", NOW.minusSeconds(20));
        when(jobs.findTicketIdByIdAndWorkspaceId(terminal.getId(), workspaceId))
                .thenReturn(Optional.of(terminal.getTicketId()));
        when(requests.replay(workspaceId, terminal.getTicketId(), terminal.getId(), "corr")).thenReturn(terminal.snapshot());

        service().retry(workspaceId, terminal.getId(), "corr");

        verify(requests).replay(workspaceId, terminal.getTicketId(), terminal.getId(), "corr");
        verify(requests, never()).request(any(), any(), any());
        verify(jobs, never()).findByIdAndWorkspaceId(any(), any());
        verify(jobs, never()).findById(any());
    }

    @Test
    void aCompletedJobCannotBeReplayed() {
        TriageJob completed = job(NOW.minusSeconds(60));
        completed.claim(NOW.minusSeconds(50));
        completed.complete(NOW.minusSeconds(40));

        assertThatThrownBy(() -> FailureRecoveryService.requireReplayable(completed, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Completed triage jobs cannot be retried");
    }

    @Test
    void aTerminalFailureAndTheAcknowledgedSyntheticFailureCanBeReplayedAtOnce() {
        TriageJob terminal = job(NOW.minusSeconds(60));
        terminal.claim(NOW.minusSeconds(50));
        terminal.terminalFailure("INVALID_EVENT", NOW.minusSeconds(40));
        TriageJob automatic = failed("PROVIDER_TIMEOUT", NOW.minusSeconds(5));
        TriageJob synthetic = failed("SYNTHETIC_PROVIDER_TIMEOUT", NOW.minusSeconds(5));

        assertThatCode(() -> FailureRecoveryService.requireReplayable(terminal, NOW)).doesNotThrowAnyException();
        assertThatCode(() -> FailureRecoveryService.requireReplayable(synthetic, NOW)).doesNotThrowAnyException();
        assertThatThrownBy(() -> FailureRecoveryService.requireReplayable(automatic, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Retryable jobs remain owned by SQS");
    }

    @Test
    void aRetryableFailureCanBeReplayedOnceSqsHasGivenUpOnIt() {
        TriageJob recent = failed("PROVIDER_TIMEOUT", NOW.minusSeconds(60));
        TriageJob abandoned = failed("PROVIDER_TIMEOUT",
                NOW.minus(FailureRecoveryService.SQS_REDELIVERY_WINDOW).minusSeconds(1));

        assertThatThrownBy(() -> FailureRecoveryService.requireReplayable(recent, NOW))
                .hasMessage("Retryable jobs remain owned by SQS");
        assertThatCode(() -> FailureRecoveryService.requireReplayable(abandoned, NOW)).doesNotThrowAnyException();
    }

    @Test
    void aQueuedOrProcessingJobCanBeReplayedOnlyOnceItIsStranded() {
        TriageJob freshQueued = job(NOW.minusSeconds(120));
        TriageJob liveProcessing = job(NOW.minusSeconds(900));
        liveProcessing.claim(NOW.minusSeconds(30));
        TriageJob strandedQueued = job(NOW.minus(FailureRecoveryService.SQS_REDELIVERY_WINDOW).minusSeconds(1));
        TriageJob strandedProcessing = job(NOW.minusSeconds(1800));
        strandedProcessing.claim(NOW.minus(FailureRecoveryService.SQS_REDELIVERY_WINDOW).minusSeconds(1));

        assertThatThrownBy(() -> FailureRecoveryService.requireReplayable(freshQueued, NOW))
                .hasMessage("Active triage jobs remain owned by SQS");
        assertThatThrownBy(() -> FailureRecoveryService.requireReplayable(liveProcessing, NOW))
                .hasMessage("Active triage jobs remain owned by SQS");
        assertThatCode(() -> FailureRecoveryService.requireReplayable(strandedQueued, NOW)).doesNotThrowAnyException();
        assertThatCode(() -> FailureRecoveryService.requireReplayable(strandedProcessing, NOW)).doesNotThrowAnyException();
    }

    private static TriageJob job(Instant createdAt) {
        return TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, createdAt);
    }

    private static TriageJob failed(String errorCode, Instant failedAt) {
        TriageJob job = job(failedAt.minusSeconds(120));
        job.claim(failedAt.minusSeconds(60));
        job.retryableFailure(errorCode, failedAt);
        return job;
    }
}
