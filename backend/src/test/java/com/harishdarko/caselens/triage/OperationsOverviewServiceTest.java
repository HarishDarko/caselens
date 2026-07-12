package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OperationsOverviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-12T12:00:00Z");

    @Mock TriageJobRepository jobs;
    @Mock TriageRequestService requests;
    @Mock TriageResultRepository results;
    @Mock ModelInvocationRepository invocations;
    private FailureRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new FailureRecoveryService(jobs, requests, results, invocations,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void summarizesQueueProviderFailuresFallbacksAndRecentActivityWithoutTicketContent() {
        UUID workspaceId = UUID.randomUUID();
        UUID fallbackTicketId = UUID.randomUUID();
        TriageJob completed = job(workspaceId, UUID.randomUUID(), fallbackTicketId);
        completed.claim(NOW.minusSeconds(2));
        completed.complete(NOW);
        TriageJob queued = job(workspaceId, UUID.randomUUID(), UUID.randomUUID());
        TriageJob processing = job(workspaceId, UUID.randomUUID(), UUID.randomUUID());
        processing.claim(NOW.minusSeconds(1));
        TriageJob retryable = job(workspaceId, UUID.randomUUID(), UUID.randomUUID());
        retryable.retryableFailure("PROVIDER_IO", NOW.minusSeconds(4));
        TriageJob terminal = job(workspaceId, UUID.randomUUID(), UUID.randomUUID());
        terminal.terminalFailure("TICKET_NOT_FOUND", NOW.minusSeconds(3));

        TriageResult fallback = result(completed.getEventId(), workspaceId, fallbackTicketId, DecisionSource.RULES_FALLBACK);
        when(jobs.findByWorkspaceId(workspaceId)).thenReturn(List.of(completed, queued, processing, retryable, terminal));
        when(results.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of(fallback));
        when(invocations.findByTicketIds(any())).thenReturn(List.of(
                ModelInvocation.from(new ModelInvocationRecord(fallbackTicketId, "groq", "openai/gpt-oss-20b", "triage-v1",
                        NOW.minusSeconds(2), NOW.minusSeconds(1), 1000, "FAILURE", "SCHEMA_FAILURE", null, null))));

        OperationsOverview overview = service.overview(workspaceId);

        assertThat(overview.queue().queued()).isEqualTo(1);
        assertThat(overview.queue().processing()).isEqualTo(1);
        assertThat(overview.queue().completed()).isEqualTo(1);
        assertThat(overview.queue().retryableFailures()).isEqualTo(1);
        assertThat(overview.queue().terminalFailures()).isEqualTo(1);
        assertThat(overview.fallbackCount()).isEqualTo(1);
        assertThat(overview.providerFailureCount()).isEqualTo(1);
        assertThat(overview.providers()).singleElement().satisfies(provider -> {
            assertThat(provider.provider()).isEqualTo("groq");
            assertThat(provider.failures()).isEqualTo(1);
        });
        assertThat(overview.recent()).hasSize(5);
        assertThat(overview.recent().getFirst().jobId()).isEqualTo(completed.getId());
        assertThat(overview.recent()).allSatisfy(operation -> {
            assertThat(operation.ticketId()).isNotNull();
            assertThat(operation.toString()).doesNotContain("subject", "message");
        });
    }

    @Test
    void limitsRecentActivityToTwentyNewestJobs() {
        UUID workspaceId = UUID.randomUUID();
        List<TriageJob> jobsInWorkspace = java.util.stream.IntStream.range(0, 25)
                .mapToObj(index -> job(workspaceId, UUID.randomUUID(), UUID.randomUUID()))
                .toList();
        when(jobs.findByWorkspaceId(workspaceId)).thenReturn(jobsInWorkspace);
        when(results.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of());
        when(invocations.findByTicketIds(any())).thenReturn(List.of());

        OperationsOverview overview = service.overview(workspaceId);

        assertThat(overview.recent()).hasSize(20);
    }

    private TriageJob job(UUID workspaceId, UUID eventId, UUID ticketId) {
        return TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, NOW.minusSeconds(10));
    }

    private TriageResult result(UUID eventId, UUID workspaceId, UUID ticketId, DecisionSource source) {
        return TriageResult.from(eventId, workspaceId, new TriageDecision(ticketId, Category.GENERAL, Urgency.MEDIUM,
                SlaRisk.MEDIUM, Sentiment.NEUTRAL, "A synthetic support request needs review.", List.of(), List.of(),
                "The request is clear enough for a reviewer to determine the next step.", List.of("Review the request"),
                "We are reviewing this request now.", ReliabilitySignal.MEDIUM, List.of(), 20, List.of(), source,
                "openai/gpt-oss-20b", "triage-v1"), NOW, new ObjectMapper());
    }
}
