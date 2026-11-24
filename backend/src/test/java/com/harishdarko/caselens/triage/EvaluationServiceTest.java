package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketChannel;
import com.harishdarko.caselens.ticket.TicketRepository;
import com.harishdarko.caselens.ticket.TicketScenarioKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

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
                Category.of("CHARGING_SESSION"), Urgency.HIGH, null, "Escalation confirmed", "demo-reviewer", FINISHED);

        when(results.findFactsByWorkspaceId(workspaceId)).thenReturn(facts(result));
        when(jobs.findByWorkspaceId(workspaceId)).thenReturn(List.of(job));
        when(tickets.findScenarioKeys(workspaceId, java.util.Set.of(ticketId))).thenReturn(List.of(TicketScenarioKey.of(ticket)));
        when(feedback.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of(correction));
        when(groundTruth.findAll()).thenReturn(List.of(new EvaluationGroundTruth("synthetic-session",
                Category.of("CHARGING_SESSION"), Urgency.HIGH)));
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
        when(results.findFactsByWorkspaceId(workspaceId)).thenReturn(facts(older, newer));
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
        when(results.findFactsByWorkspaceId(workspaceId)).thenReturn(facts(fallback));
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
        when(results.findFactsByWorkspaceId(workspaceId)).thenReturn(facts(result));
        when(jobs.findByWorkspaceId(workspaceId)).thenReturn(List.of());
        when(tickets.findScenarioKeys(workspaceId, java.util.Set.of(ticketId))).thenReturn(List.of(TicketScenarioKey.of(ticket)));
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
        when(results.findFactsByWorkspaceId(workspaceId)).thenReturn(facts());
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
        when(results.findFactsByWorkspaceId(workspaceId)).thenReturn(facts());
        when(jobs.findByWorkspaceId(workspaceId)).thenReturn(List.of(original, replay));
        when(feedback.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of());
        when(groundTruth.findAll()).thenReturn(List.of());

        assertThat(service().summarize(workspaceId).failureRate()).isEqualTo(0.0);
    }

    @Test
    void recordedResultsNeverReachLiveLatencyProviderUsageOrRecentActivity() {
        UUID workspaceId = UUID.randomUUID();
        UUID liveTicketId = UUID.randomUUID();
        UUID recordedTicketId = UUID.randomUUID();
        TriageResult live = result(workspaceId, liveTicketId);
        TriageResult recorded = recordedAt(workspaceId, recordedTicketId, CREATED.minusSeconds(3600));
        TriageJob job = TriageJob.queued(UUID.randomUUID(), live.getEventId(), workspaceId, liveTicketId, 1, CREATED);
        job.claim(CREATED.plusMillis(100));
        job.complete(FINISHED);
        when(results.findFactsByWorkspaceId(workspaceId)).thenReturn(facts(recorded, live));
        when(jobs.findByWorkspaceId(workspaceId)).thenReturn(List.of(job));
        when(feedback.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of());
        when(groundTruth.findAll()).thenReturn(List.of());
        when(invocations.findByTicketIds(List.of(liveTicketId))).thenReturn(List.of(ModelInvocation.from(
                new ModelInvocationRecord(liveTicketId, "mock", "mock-v1", "triage-v1", CREATED, CREATED.plusMillis(50),
                        50, "SUCCESS", null, 120L, 40L))));

        EvaluationSummary summary = service().summarize(workspaceId);

        assertThat(summary.triageResults()).isEqualTo(1);
        assertThat(summary.recordedResults()).isEqualTo(1);
        assertThat(summary.medianLatencyMs()).isEqualTo(2000L);
        assertThat(summary.p95LatencyMs()).isEqualTo(2000L);
        assertThat(summary.providerUsage()).singleElement().satisfies(usage -> assertThat(usage.requests()).isEqualTo(1));
        assertThat(summary.recentEvents()).extracting(EvaluationEvent::resultId).containsExactly(live.getId());
    }

    @Test
    void aWorkspaceWithOnlyRecordedResultsHasNullLatenciesAndRatesNotZero() {
        UUID workspaceId = UUID.randomUUID();
        TriageResult first = recordedAt(workspaceId, UUID.randomUUID(), CREATED.minusSeconds(60));
        TriageResult second = recordedAt(workspaceId, UUID.randomUUID(), CREATED.minusSeconds(30));
        when(results.findFactsByWorkspaceId(workspaceId)).thenReturn(facts(first, second));
        when(jobs.findByWorkspaceId(workspaceId)).thenReturn(List.of());
        when(feedback.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of());
        when(groundTruth.findAll()).thenReturn(List.of());
        when(tickets.findScenarioKeys(org.mockito.ArgumentMatchers.eq(workspaceId), org.mockito.ArgumentMatchers.anySet()))
                .thenReturn(List.of());

        EvaluationSummary summary = service().summarize(workspaceId);

        assertThat(summary.recordedResults()).isEqualTo(2);
        assertThat(summary.triageResults()).isZero();
        assertThat(summary.medianLatencyMs()).isNull();
        assertThat(summary.p95LatencyMs()).isNull();
        assertThat(summary.failureRate()).isNull();
        assertThat(summary.correctionRate()).isNull();
        assertThat(summary.agreementRate()).isNull();
        assertThat(summary.categoryAgreementRate()).isNull();
        assertThat(summary.urgencyAgreementRate()).isNull();
        assertThat(summary.reviewedResults()).isZero();
        assertThat(summary.acceptedResults()).isZero();
        assertThat(summary.acceptanceRate()).isNull();
        assertThat(summary.overrideRate()).isNull();
        assertThat(summary.providerUsage()).isEmpty();
        assertThat(summary.recentEvents()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(invocations);
    }

    @Test
    void acceptanceAndOverrideRatesCountEachResultsLatestReviewIncludingRecordedResults() {
        UUID workspaceId = UUID.randomUUID();
        UUID ticketA = UUID.randomUUID();
        UUID ticketB = UUID.randomUUID();
        UUID ticketC = UUID.randomUUID();
        TriageResult accepted = recordedAt(workspaceId, ticketA, CREATED.minusSeconds(60));
        TriageResult corrected = recordedAt(workspaceId, ticketB, CREATED.minusSeconds(50));
        TriageResult unreviewed = recordedAt(workspaceId, ticketC, CREATED.minusSeconds(40));
        TriageFeedback firstCorrection = TriageFeedback.submit(UUID.randomUUID(), workspaceId, ticketA, accepted.getId(),
                null, Urgency.HIGH, null, "Changed my mind", "demo-reviewer", CREATED.minusSeconds(30));
        TriageFeedback thenAccepted = TriageFeedback.submit(UUID.randomUUID(), workspaceId, ticketA, accepted.getId(),
                FeedbackDecision.ACCEPTED, null, null, null, null, "demo-reviewer", CREATED.minusSeconds(20));
        TriageFeedback override = TriageFeedback.submit(UUID.randomUUID(), workspaceId, ticketB, corrected.getId(),
                null, Urgency.CRITICAL, null, "Escalate", "demo-reviewer", CREATED.minusSeconds(10));
        when(results.findFactsByWorkspaceId(workspaceId)).thenReturn(facts(accepted, corrected, unreviewed));
        when(jobs.findByWorkspaceId(workspaceId)).thenReturn(List.of());
        when(feedback.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId))
                .thenReturn(List.of(firstCorrection, thenAccepted, override));
        when(groundTruth.findAll()).thenReturn(List.of());
        when(tickets.findScenarioKeys(org.mockito.ArgumentMatchers.eq(workspaceId), org.mockito.ArgumentMatchers.anySet()))
                .thenReturn(List.of());

        EvaluationSummary summary = service().summarize(workspaceId);

        assertThat(summary.reviewedResults()).isEqualTo(2);
        assertThat(summary.acceptedResults()).isEqualTo(1);
        assertThat(summary.acceptanceRate()).isEqualTo(0.5);
        assertThat(summary.overrideRate()).isEqualTo(0.5);
        assertThat(summary.recordedResults()).isEqualTo(3);
    }

    @Test
    void correctionRateCountsLiveResultsOnlyToMatchTriageResults() {
        UUID workspaceId = UUID.randomUUID();
        UUID liveTicketA = UUID.randomUUID();
        UUID liveTicketB = UUID.randomUUID();
        UUID recordedTicket = UUID.randomUUID();
        TriageResult liveCorrected = resultAt(workspaceId, liveTicketA, CREATED);
        TriageResult liveUntouched = resultAt(workspaceId, liveTicketB, CREATED.plusSeconds(1));
        TriageResult recorded = recordedAt(workspaceId, recordedTicket, CREATED.minusSeconds(60));
        TriageFeedback liveCorrection = TriageFeedback.submit(UUID.randomUUID(), workspaceId, liveTicketA,
                liveCorrected.getId(), null, Urgency.CRITICAL, null, "Escalate", "demo-reviewer", CREATED.plusSeconds(5));
        TriageFeedback recordedCorrection = TriageFeedback.submit(UUID.randomUUID(), workspaceId, recordedTicket,
                recorded.getId(), null, Urgency.CRITICAL, null, "Escalate", "demo-reviewer", CREATED.plusSeconds(6));
        when(results.findFactsByWorkspaceId(workspaceId)).thenReturn(facts(recorded, liveCorrected, liveUntouched));
        when(jobs.findByWorkspaceId(workspaceId)).thenReturn(List.of());
        when(feedback.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId))
                .thenReturn(List.of(liveCorrection, recordedCorrection));
        when(groundTruth.findAll()).thenReturn(List.of());

        EvaluationSummary summary = service().summarize(workspaceId);

        assertThat(summary.triageResults()).isEqualTo(2);
        assertThat(summary.recordedResults()).isEqualTo(1);
        // One of two live results corrected; the corrected recorded result is neither numerator nor denominator.
        assertThat(summary.correctionRate()).isEqualTo(0.5);
    }

    @Test
    void aLiveResultWithoutAJobHasNullEventLatencyNotZero() {
        UUID workspaceId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        TriageResult live = result(workspaceId, ticketId);
        when(results.findFactsByWorkspaceId(workspaceId)).thenReturn(facts(live));
        when(jobs.findByWorkspaceId(workspaceId)).thenReturn(List.of());
        when(feedback.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)).thenReturn(List.of());
        when(groundTruth.findAll()).thenReturn(List.of());
        when(invocations.findByTicketIds(List.of(ticketId))).thenReturn(List.of());

        EvaluationSummary summary = service().summarize(workspaceId);

        assertThat(summary.recentEvents()).singleElement().satisfies(event -> assertThat(event.latencyMs()).isNull());
    }

    private TriageResult recordedAt(UUID workspaceId, UUID ticketId, Instant capturedAt) {
        return TriageResult.recorded(workspaceId,
                new TriageDecision(ticketId, Category.of("CHARGING_SESSION"), Urgency.MEDIUM, SlaRisk.MEDIUM,
                        Sentiment.NEGATIVE, "Charging session did not start", List.of(), List.of(),
                        "The session did not start.", List.of("Inspect the charger"), "We are reviewing it.",
                        ReliabilitySignal.HIGH, List.of(), 55, List.of(), DecisionSource.AI_VALIDATED,
                        "openai/gpt-oss-20b", "triage-v2"),
                capturedAt, new ObjectMapper());
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
                new TriageDecision(ticketId, Category.of("CHARGING_SESSION"), Urgency.MEDIUM, SlaRisk.MEDIUM,
                        Sentiment.NEGATIVE, "Charging session did not start", List.of(), List.of(),
                        "The session did not start.", List.of("Inspect the charger"), "We are reviewing it.",
                        ReliabilitySignal.HIGH, List.of(), 55, List.of(), source, "mock-v1", "triage-v1"),
                createdAt, new ObjectMapper());
        return result;
    }

    private static List<ResultFacts> facts(TriageResult... stored) {
        return java.util.Arrays.stream(stored).map(ResultFacts::of).toList();
    }
}
