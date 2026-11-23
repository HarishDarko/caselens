package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketChannel;
import com.harishdarko.caselens.ticket.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class TriageWorkerTest {
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-11T14:00:00Z"), ZoneOffset.UTC);

    @Mock TriageJobRepository jobs;
    @Mock TicketRepository tickets;
    @Mock TriageResultRepository results;

    @Test
    void acknowledgesDuplicateDeliveryWithoutCallingProviderAgain() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, clock.instant());
        job.claim(clock.instant());
        job.complete(clock.instant());
        when(jobs.findByEventId(eventId)).thenReturn(Optional.of(job));

        TriageWorker worker = worker(new MockTriageProvider());
        WorkerResult result = worker.process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1));

        assertThat(result.status()).isEqualTo(WorkerResultStatus.ACK_DUPLICATE);
        verify(tickets, never()).findByIdAndWorkspaceId(any(), any());
    }

    @Test
    void acknowledgesALateMessageForAStrandedJobThatWasClosedAndReplayedWithoutCallingTheProvider() {
        // CL2-03: a reviewer replay closed the stranded job as SQS_DELIVERY_EXHAUSTED; then a DLQ redrive (or a
        // late relay publish) delivers the original message. The claim fails, so no provider call is made.
        TriageJob closed = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, clock.instant());
        closed.terminalFailure(TriageRequestService.SQS_DELIVERY_EXHAUSTED, clock.instant());
        when(jobs.findByEventId(eventId)).thenReturn(Optional.of(closed));
        when(jobs.claimForProcessing(eq(eventId), any(Instant.class))).thenReturn(0);
        java.util.concurrent.atomic.AtomicInteger providerCalls = new java.util.concurrent.atomic.AtomicInteger();
        TriageProvider countingProvider = (request, redacted) -> {
            providerCalls.incrementAndGet();
            return new MockTriageProvider().analyze(request, redacted);
        };

        WorkerResult result = worker(countingProvider).process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1));

        assertThat(result.status()).isEqualTo(WorkerResultStatus.ACK_DUPLICATE);
        assertThat(providerCalls).hasValue(0);
        verify(tickets, never()).findByIdAndWorkspaceId(any(), any());
    }

    @Test
    void terminallyRejectsMessageThatDoesNotMatchTheStoredJobScope() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, clock.instant());
        when(jobs.findByEventId(eventId)).thenReturn(Optional.of(job));

        WorkerResult result = worker(new MockTriageProvider()).process(
                new QueueMessage(eventId, UUID.randomUUID(), ticketId, "corr", 1));

        assertThat(result.status()).isEqualTo(WorkerResultStatus.TERMINAL_FAILURE);
        assertThat(job.getLastErrorCode()).isEqualTo("INVALID_EVENT");
        verify(tickets, never()).findByIdAndWorkspaceId(any(), any());
    }

    @Test
    void returnsRetryForTransientProviderFailure() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, clock.instant());
        Ticket ticket = ticket();
        when(jobs.findByEventId(eventId)).thenReturn(Optional.of(job));
        when(jobs.claimForProcessing(eq(eventId), any(Instant.class))).thenAnswer(invocation -> {
            job.claim(invocation.getArgument(1));
            return 1;
        });
        when(tickets.findByIdAndWorkspaceId(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        stillOwnsTheClaim();
        TriageProvider transientFailure = (request, redacted) -> {
            throw new ProviderCallException("provider unavailable", "HTTP_503");
        };

        WorkerResult result = workerWithEngine(failureEngine(transientFailure)).process(
                new QueueMessage(eventId, workspaceId, ticketId, "corr", 1));

        assertThat(result.status()).isEqualTo(WorkerResultStatus.RETRY);
        assertThat(job.getStatus()).isEqualTo(TriageJobStatus.RETRYABLE_FAILURE);
        assertThat(job.getLastErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
    }

    @Test
    void recordsOneControlledRetryForTheFixedDemoScenario() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, clock.instant());
        Ticket ticket = Ticket.create(ticketId, workspaceId, "Synthetic provider timeout",
                "This reserved synthetic case demonstrates a bounded provider retry.",
                TicketChannel.WEB, "provider-retry-demo", clock.instant());
        when(jobs.findByEventId(eventId)).thenReturn(Optional.of(job));
        when(jobs.claimForProcessing(eq(eventId), any(Instant.class))).thenAnswer(invocation -> {
            job.claim(invocation.getArgument(1));
            return 1;
        });
        when(tickets.findByIdAndWorkspaceId(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        stillOwnsTheClaim();

        TriageWorker worker = new TriageWorker(jobs, tickets, results, null,
                transientEngine(new MockTriageProvider()), new ObjectMapper(), clock,
                TriageMetrics.noop(), new DemoFailurePolicy(true));
        WorkerResult result = worker.process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1));

        assertThat(result.status()).isEqualTo(WorkerResultStatus.ACK);
        assertThat(job.getStatus()).isEqualTo(TriageJobStatus.RETRYABLE_FAILURE);
        assertThat(job.getLastErrorCode()).isEqualTo("SYNTHETIC_PROVIDER_TIMEOUT");
        verify(results, never()).save(any());
    }

    @Test
    void storesOneResultAndCompletesTheTicketOnSuccess() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, clock.instant());
        Ticket ticket = ticket();
        when(jobs.findByEventId(eventId)).thenReturn(Optional.of(job));
        when(jobs.claimForProcessing(eq(eventId), any(Instant.class))).thenReturn(1);
        when(tickets.findByIdAndWorkspaceId(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        stillOwnsTheClaim();
        when(results.save(any(TriageResult.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkerResult result = worker(new MockTriageProvider()).process(
                new QueueMessage(eventId, workspaceId, ticketId, "corr", 1));

        assertThat(result.status()).isEqualTo(WorkerResultStatus.ACK);
        assertThat(job.getStatus()).isEqualTo(TriageJobStatus.COMPLETED);
        assertThat(ticket.getStatus()).isEqualTo(com.harishdarko.caselens.ticket.TicketStatus.COMPLETED);
        verify(results).save(any(TriageResult.class));
    }

    @Test
    void logsOneStructuredWarningWithTheReasonWhenTheRulesFallbackDecides() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, clock.instant());
        Ticket ticket = ticket();
        when(jobs.findByEventId(eventId)).thenReturn(Optional.of(job));
        when(jobs.claimForProcessing(eq(eventId), any(Instant.class))).thenReturn(1);
        when(tickets.findByIdAndWorkspaceId(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        stillOwnsTheClaim();
        when(results.save(any(TriageResult.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TriageProvider rateLimited = new TriageProvider() {
            @Override
            public ProviderCall analyze(TriageRequest request, RedactedTicket redacted) {
                throw new ProviderCallException("Groq provider request failed", "HTTP_429");
            }

            @Override public String providerName() { return "groq"; }
        };

        List<ILoggingEvent> lines = captureWorkerLog(() -> {
            WorkerResult result = worker(rateLimited).process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1));
            assertThat(result.status()).isEqualTo(WorkerResultStatus.ACK);
        });

        List<ILoggingEvent> fallbacks = lines.stream()
                .filter(line -> line.getFormattedMessage().startsWith("triage.fallback ")).toList();
        assertThat(fallbacks).hasSize(1);
        ILoggingEvent line = fallbacks.getFirst();
        assertThat(line.getLevel()).isEqualTo(Level.WARN);
        assertThat(line.getFormattedMessage())
                .contains("ticketId=" + ticketId, "jobId=" + job.getId(), "eventId=" + eventId, "pack=ev-charging",
                        "provider=groq", "errorCode=HTTP_429", "check=none", "reason=PROVIDER_RATE_LIMITED")
                .doesNotContain(ticket.getSubject(), ticket.getMessage());
    }

    @Test
    void logsTheFailedCheckWhenTheAnswerFailsValidationAndNeverTheTicketTextOrQuotes() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, clock.instant());
        Ticket ticket = ticket();
        when(jobs.findByEventId(eventId)).thenReturn(Optional.of(job));
        when(jobs.claimForProcessing(eq(eventId), any(Instant.class))).thenReturn(1);
        when(tickets.findByIdAndWorkspaceId(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        stillOwnsTheClaim();
        when(results.save(any(TriageResult.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TriageProvider invented = (request, redacted) -> new ProviderCall(new ProviderTriageResult(
                Category.of("CHARGING_SESSION"), Urgency.HIGH, SlaRisk.HIGH, Sentiment.NEGATIVE,
                "The provider returned an invented evidence quote.",
                List.of(new EvidenceItem("An invented quote about a fire", "Not in the ticket")), List.of(),
                "The provider output cannot be trusted because its evidence is not grounded in the ticket.",
                List.of("Verify the session state"),
                "We are reviewing the session state and will update you after verification.",
                List.of(), "model-x", "triage-v2"), null, null);

        List<ILoggingEvent> lines = captureWorkerLog(() ->
                worker(invented).process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1)));

        String line = lines.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("triage.fallback ")).findFirst().orElseThrow();
        assertThat(line).contains("errorCode=SEMANTIC_VALIDATION", "check=QUOTE_NOT_FOUND", "reason=ANSWER_FAILED_CHECKS")
                .doesNotContain("invented", ticket.getSubject(), ticket.getMessage());
    }

    @Test
    void logsNoFallbackLineWhenTheAiAnswerIsValidated() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, clock.instant());
        when(jobs.findByEventId(eventId)).thenReturn(Optional.of(job));
        when(jobs.claimForProcessing(eq(eventId), any(Instant.class))).thenReturn(1);
        when(tickets.findByIdAndWorkspaceId(ticketId, workspaceId)).thenReturn(Optional.of(ticket()));
        stillOwnsTheClaim();
        when(results.save(any(TriageResult.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<ILoggingEvent> lines = captureWorkerLog(() ->
                worker(new MockTriageProvider()).process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1)));

        assertThat(lines).noneMatch(line -> line.getFormattedMessage().startsWith("triage.fallback "));
    }

    @Test
    void asksForRedeliveryWhenAnotherWorkerHoldsTheAtomicClaim() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, clock.instant());
        TriageProvider provider = org.mockito.Mockito.mock(TriageProvider.class);
        when(jobs.findByEventId(eventId)).thenReturn(Optional.of(job));
        when(jobs.claimForProcessing(eq(eventId), any(Instant.class))).thenAnswer(invocation -> {
            job.claim(invocation.getArgument(1)); // another worker's claim is live, so this one changes no row
            return 0;
        });

        WorkerResult result = workerWithEngine(transientEngine(provider)).process(
                new QueueMessage(eventId, workspaceId, ticketId, "corr", 1));

        assertThat(result.status()).isEqualTo(WorkerResultStatus.RETRY);
        assertThat(result.errorCode()).isEqualTo("CLAIM_HELD");
        verify(provider, never()).analyze(any(), any());
        verify(tickets, never()).findByIdAndWorkspaceId(any(), any());
    }

    @Test
    void dropsTheFinalWritesWhenTheClaimWasTakenOverDuringTheProviderCall() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, clock.instant());
        Ticket ticket = ticket();
        when(jobs.findByEventId(eventId)).thenReturn(Optional.of(job));
        when(jobs.claimForProcessing(eq(eventId), any(Instant.class))).thenAnswer(invocation -> {
            job.claim(invocation.getArgument(1));
            return 1;
        });
        when(tickets.findByIdAndWorkspaceId(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        when(jobs.holdClaim(eq(eventId), eq(1), any(Instant.class))).thenReturn(0);
        TriageProvider transientFailure = (request, redacted) -> {
            throw new ProviderCallException("provider unavailable", "HTTP_503");
        };

        WorkerResult result = workerWithEngine(failureEngine(transientFailure)).process(
                new QueueMessage(eventId, workspaceId, ticketId, "corr", 1));

        assertThat(result.status()).isEqualTo(WorkerResultStatus.RETRY);
        assertThat(result.errorCode()).isEqualTo("CLAIM_LOST");
        assertThat(job.getStatus()).isEqualTo(TriageJobStatus.PROCESSING);
        verify(jobs, never()).save(any());
        verify(tickets).save(ticket); // once, by the claim; not again by the dropped failure write
        verify(results, never()).save(any());
    }

    private List<ILoggingEvent> captureWorkerLog(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(TriageWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return List.copyOf(appender.list);
    }

    private void stillOwnsTheClaim() {
        when(jobs.holdClaim(eq(eventId), any(Integer.class), any(Instant.class))).thenReturn(1);
    }

    private TriageWorker worker(TriageProvider provider) {
        return new TriageWorker(jobs, tickets, results, transientEngine(provider), new ObjectMapper(), clock);
    }

    private TriageWorker workerWithEngine(TriageEngine engine) {
        return new TriageWorker(jobs, tickets, results, engine, new ObjectMapper(), clock);
    }

    private TriageEngine transientEngine(TriageProvider provider) {
        return new TriageEngine(new TicketRedactor(), provider, new RulesFallbackProvider(),
                new SemanticValidator(PolicyCatalog.defaultCatalog()), new PriorityScorer(), ignored -> {}, clock);
    }

    private TriageEngine failureEngine(TriageProvider provider) {
        return new TriageEngine(new TicketRedactor(), provider, (request, ticket) -> {
            throw new ProviderCallException("provider unavailable", "HTTP_503");
        }, new SemanticValidator(PolicyCatalog.defaultCatalog()), new PriorityScorer(), ignored -> {}, clock);
    }

    private Ticket ticket() {
        return Ticket.create(ticketId, workspaceId, "Payment issue",
                "Payment was captured but the session never started for this ticket.", TicketChannel.WEB, null, clock.instant());
    }
}
