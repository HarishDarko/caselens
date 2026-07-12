package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketChannel;
import com.harishdarko.caselens.ticket.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvaluationServiceTest {
    private static final Instant CREATED = Instant.parse("2026-07-11T15:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-07-11T15:00:02Z");

    @Mock TriageResultRepository results;
    @Mock TriageJobRepository jobs;
    @Mock TicketRepository tickets;
    @Mock TriageFeedbackRepository feedback;
    @Mock EvaluationGroundTruthRepository groundTruth;
    @Mock ModelInvocationRepository invocations;

    @Test
    void summarizesAgreementCorrectionsLatencyFailuresAndProviderUsageFromStoredEvents() {
        UUID workspaceId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        TriageResult result = result(workspaceId, ticketId);
        Ticket ticket = Ticket.create(ticketId, workspaceId, "Synthetic session issue",
                "Synthetic ticket message that is long enough for evaluation.", TicketChannel.WEB, "synthetic-session", CREATED);
        TriageJob job = TriageJob.queued(UUID.randomUUID(), result.getEventId(), workspaceId, ticketId, 1, CREATED);
        job.claim(CREATED.plusMillis(100));
        job.complete(FINISHED);
        TriageFeedback correction = TriageFeedback.submit(UUID.randomUUID(), workspaceId, ticketId, result.getId(),
                Category.CHARGING_SESSION, Urgency.HIGH, null, "Escalation confirmed", "demo-reviewer", FINISHED);

        when(results.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of(result));
        when(jobs.findByWorkspaceId(workspaceId)).thenReturn(List.of(job));
        when(tickets.findByIdAndWorkspaceId(ticketId, workspaceId)).thenReturn(java.util.Optional.of(ticket));
        when(feedback.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of(correction));
        when(groundTruth.findAll()).thenReturn(List.of(new EvaluationGroundTruth("synthetic-session",
                Category.CHARGING_SESSION, Urgency.HIGH)));
        when(invocations.findByTicketIds(List.of(ticketId))).thenReturn(List.of(ModelInvocation.from(
                new ModelInvocationRecord(ticketId, "mock", "mock-v1", "triage-v1", CREATED, CREATED.plusMillis(50),
                        50, "SUCCESS", null, 120L, 40L))));

        EvaluationSummary summary = new EvaluationService(results, jobs, tickets, feedback, groundTruth, invocations,
                Clock.fixed(FINISHED, ZoneOffset.UTC)).summarize(workspaceId);

        assertThat(summary.triageResults()).isEqualTo(1);
        assertThat(summary.evaluatedResults()).isEqualTo(1);
        assertThat(summary.categoryAgreementRate()).isEqualTo(1.0);
        assertThat(summary.urgencyAgreementRate()).isEqualTo(0.0);
        assertThat(summary.agreementRate()).isEqualTo(0.0);
        assertThat(summary.correctionRate()).isEqualTo(1.0);
        assertThat(summary.medianLatencyMs()).isEqualTo(2000L);
        assertThat(summary.p95LatencyMs()).isEqualTo(2000L);
        assertThat(summary.failureRate()).isEqualTo(0.0);
        assertThat(summary.providerUsage()).singleElement().satisfies(usage -> {
            assertThat(usage.provider()).isEqualTo("mock");
            assertThat(usage.requests()).isEqualTo(1);
            assertThat(usage.inputTokens()).isEqualTo(120L);
            assertThat(usage.outputTokens()).isEqualTo(40L);
        });
        assertThat(summary.recentEvents()).singleElement().satisfies(event -> {
            assertThat(event.provider()).isEqualTo("mock");
            assertThat(event.modelVersion()).isEqualTo("mock-v1");
            assertThat(event.decisionSource()).isEqualTo("AI_VALIDATED");
            assertThat(event.evaluated()).isTrue();
            assertThat(event.corrected()).isTrue();
            assertThat(event.latencyMs()).isEqualTo(2000L);
        });
    }

    @Test
    void returnsRecentEvaluationEventsNewestFirst() {
        UUID workspaceId = UUID.randomUUID();
        UUID olderTicketId = UUID.randomUUID();
        UUID newerTicketId = UUID.randomUUID();
        TriageResult older = resultAt(workspaceId, olderTicketId, FINISHED.minusSeconds(1));
        TriageResult newer = resultAt(workspaceId, newerTicketId, FINISHED);
        when(results.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of(older, newer));
        when(jobs.findByWorkspaceId(workspaceId)).thenReturn(List.of());
        when(feedback.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of());
        when(groundTruth.findAll()).thenReturn(List.of());
        when(invocations.findByTicketIds(List.of(olderTicketId, newerTicketId))).thenReturn(List.of());

        EvaluationSummary summary = service().summarize(workspaceId);

        assertThat(summary.recentEvents()).extracting(EvaluationEvent::resultId)
                .containsExactly(newer.getId(), older.getId());
    }

    @Test
    void reportsFallbackAndProviderFailureInRecentEvents() {
        UUID workspaceId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        TriageResult fallback = result(workspaceId, ticketId, DecisionSource.RULES_FALLBACK);
        TriageJob job = TriageJob.queued(UUID.randomUUID(), fallback.getEventId(), workspaceId, ticketId, 1, CREATED);
        job.claim(CREATED.plusMillis(100));
        job.complete(FINISHED);
        when(results.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of(fallback));
        when(jobs.findByWorkspaceId(workspaceId)).thenReturn(List.of(job));
        when(feedback.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of());
        when(groundTruth.findAll()).thenReturn(List.of());
        when(invocations.findByTicketIds(List.of(ticketId))).thenReturn(List.of(ModelInvocation.from(
                new ModelInvocationRecord(ticketId, "groq", "openai/gpt-oss-20b", "triage-v1", CREATED,
                        CREATED.plusMillis(800), 800, "FAILURE", "SCHEMA_FAILURE", null, null))));

        EvaluationSummary summary = service().summarize(workspaceId);

        assertThat(summary.recentEvents()).singleElement().satisfies(event -> {
            assertThat(event.provider()).isEqualTo("groq");
            assertThat(event.decisionSource()).isEqualTo("RULES_FALLBACK");
            assertThat(event.evaluated()).isFalse();
            assertThat(event.corrected()).isFalse();
        });
    }

    @Test
    void selectsTheDeterministicLatestFeedbackWhenTimestampsTie() {
        UUID workspaceId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        TriageResult result = result(workspaceId, ticketId);
        Ticket ticket = Ticket.create(ticketId, workspaceId, "Synthetic session issue",
                "Synthetic ticket message that is long enough for evaluation.", TicketChannel.WEB, "synthetic-session", CREATED);
        TriageFeedback sameValue = TriageFeedback.submit(new UUID(0, 1), workspaceId, ticketId, result.getId(),
                null, Urgency.MEDIUM, null, "First review", "demo-reviewer", FINISHED);
        TriageFeedback laterId = TriageFeedback.submit(new UUID(0, 2), workspaceId, ticketId, result.getId(),
                null, Urgency.HIGH, null, "Escalation review", "demo-reviewer", FINISHED);
        when(results.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of(result));
        when(jobs.findByWorkspaceId(workspaceId)).thenReturn(List.of());
        when(tickets.findByIdAndWorkspaceId(ticketId, workspaceId)).thenReturn(java.util.Optional.of(ticket));
        when(feedback.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of(sameValue, laterId));
        when(groundTruth.findAll()).thenReturn(List.of());
        when(invocations.findByTicketIds(List.of(ticketId))).thenReturn(List.of());

        EvaluationSummary summary = service().summarize(workspaceId);

        assertThat(summary.correctionRate()).isEqualTo(1.0);
    }

    @Test
    void calculatesFinalFailureRateWithoutCountingInFlightOrRetryableJobs() {
        UUID workspaceId = UUID.randomUUID();
        TriageJob completed = job(workspaceId);
        completed.claim(CREATED.plusMillis(10));
        completed.complete(CREATED.plusMillis(20));
        TriageJob queued = job(workspaceId);
        TriageJob retryable = job(workspaceId);
        retryable.claim(CREATED.plusMillis(10));
        retryable.retryableFailure("PROVIDER_TIMEOUT", CREATED.plusMillis(20));
        TriageJob terminal = job(workspaceId);
        terminal.claim(CREATED.plusMillis(10));
        terminal.terminalFailure("SCHEMA_INVALID", CREATED.plusMillis(20));
        when(results.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of());
        when(jobs.findByWorkspaceId(workspaceId)).thenReturn(List.of(completed, queued, retryable, terminal));
        when(feedback.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of());
        when(groundTruth.findAll()).thenReturn(List.of());

        EvaluationSummary summary = service().summarize(workspaceId);

        assertThat(summary.failureRate()).isEqualTo(0.5);
    }

    @Test
    void calculatesFailureRateFromTheLatestReplayOutcome() {
        UUID workspaceId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        TriageJob original = TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), workspaceId, ticketId, 1, CREATED);
        original.claim(CREATED.plusMillis(10));
        original.terminalFailure("PROVIDER_TIMEOUT", CREATED.plusMillis(20));
        TriageJob replay = TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), workspaceId, ticketId, 1,
                CREATED.plusSeconds(1));
        replay.setReplayedFromJobId(original.getId());
        replay.claim(CREATED.plusSeconds(1).plusMillis(10));
        replay.complete(CREATED.plusSeconds(1).plusMillis(20));
        when(results.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of());
        when(jobs.findByWorkspaceId(workspaceId)).thenReturn(List.of(original, replay));
        when(feedback.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of());
        when(groundTruth.findAll()).thenReturn(List.of());

        assertThat(service().summarize(workspaceId).failureRate()).isEqualTo(0.0);
    }

    private EvaluationService service() {
        return new EvaluationService(results, jobs, tickets, feedback, groundTruth, invocations,
                Clock.fixed(FINISHED, ZoneOffset.UTC));
    }

    private TriageJob job(UUID workspaceId) {
        return TriageJob.queued(UUID.randomUUID(), UUID.randomUUID(), workspaceId, UUID.randomUUID(), 1, CREATED);
    }

    private TriageResult result(UUID workspaceId, UUID ticketId) {
        return result(workspaceId, ticketId, DecisionSource.AI_VALIDATED);
    }

    private TriageResult result(UUID workspaceId, UUID ticketId, DecisionSource source) {
        return resultAt(workspaceId, ticketId, source, FINISHED);
    }

    private TriageResult resultAt(UUID workspaceId, UUID ticketId, Instant createdAt) {
        return resultAt(workspaceId, ticketId, DecisionSource.AI_VALIDATED, createdAt);
    }

    private TriageResult resultAt(UUID workspaceId, UUID ticketId, DecisionSource source, Instant createdAt) {
        TriageResult result = TriageResult.from(UUID.randomUUID(), workspaceId,
                new TriageDecision(ticketId, Category.CHARGING_SESSION, Urgency.MEDIUM, SlaRisk.MEDIUM,
                        Sentiment.NEGATIVE, "Charging session did not start", List.of(), List.of(),
                        "The session did not start.", List.of("Inspect the charger"), "We are reviewing it.",
                        ReliabilitySignal.HIGH, List.of(), 55, List.of(), source, "mock-v1", "triage-v1"),
                createdAt, new ObjectMapper());
        return result;
    }
}
