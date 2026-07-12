package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
}
