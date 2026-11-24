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
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.read.ListAppender;
import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketChannel;
import com.harishdarko.caselens.ticket.TicketRepository;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
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
    void aRateLimitOnAnEarlyDeliveryLeavesTheJobForSqsToRetryInsteadOfFallingBack() {
        TriageJob job = claimableJob();
        List<ILoggingEvent> lines = captureWorkerLog(() -> {
            WorkerResult result = worker(rateLimited()).process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1), 1);

            assertThat(result.status()).isEqualTo(WorkerResultStatus.RETRY);
            assertThat(result.errorCode()).isEqualTo("PROVIDER_RATE_LIMIT");
        });

        assertThat(job.getStatus()).isEqualTo(TriageJobStatus.RETRYABLE_FAILURE);
        assertThat(job.getLastErrorCode()).isEqualTo("PROVIDER_RATE_LIMIT");
        verify(results, never()).save(any());
        assertThat(lines).noneMatch(line -> line.getFormattedMessage().startsWith("triage.fallback "));
    }

    @Test
    void theFinalDeliveryFallsBackToTheRulesWithTheRateLimitReason() {
        TriageJob job = claimableJob();
        when(results.save(any(TriageResult.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<ILoggingEvent> lines = captureWorkerLog(() -> {
            WorkerResult result = worker(rateLimited()).process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1),
                    TriageWorker.RATE_LIMIT_DELIVERIES);
            assertThat(result.status()).isEqualTo(WorkerResultStatus.ACK);
        });

        assertThat(job.getStatus()).isEqualTo(TriageJobStatus.COMPLETED);
        verify(results).save(any(TriageResult.class));
        assertThat(lines).anyMatch(line -> line.getFormattedMessage().startsWith("triage.fallback ")
                && line.getFormattedMessage().contains("reason=PROVIDER_RATE_LIMITED"));
    }

    @Test
    void writesTheFallbackLineOnlyAfterTheResultTransactionCommits() {
        claimableJob();
        when(results.save(any(TriageResult.class))).thenAnswer(invocation -> invocation.getArgument(0));
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        TransactionOperations committing = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                T result = action.doInTransaction(null);
                order.add("commit");
                return result;
            }
        };

        Logger logger = (Logger) LoggerFactory.getLogger(TriageWorker.class);
        AppenderBase<ILoggingEvent> live = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getFormattedMessage().startsWith("triage.completed ")) order.add("completed");
                if (event.getFormattedMessage().startsWith("triage.fallback ")) order.add("fallback");
            }
        };
        live.start();
        logger.addAppender(live);
        try {
            transactionalWorker(rateLimited(), committing)
                    .process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1), TriageWorker.RATE_LIMIT_DELIVERIES);
        } finally {
            logger.detachAppender(live);
        }

        assertThat(order).containsExactly("commit", "commit", "completed", "fallback");
    }

    @Test
    void writesNoFallbackLineWhenTheResultTransactionFailsToCommit() {
        claimableJob();
        when(results.save(any(TriageResult.class))).thenAnswer(invocation -> invocation.getArgument(0));
        java.util.concurrent.atomic.AtomicInteger transactionsRun = new java.util.concurrent.atomic.AtomicInteger();
        TransactionOperations failingSecondCommit = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                T result = action.doInTransaction(null);
                if (transactionsRun.incrementAndGet() == 2) throw new IllegalStateException("commit failed");
                return result;
            }
        };

        List<ILoggingEvent> lines = captureWorkerLog(() -> org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                transactionalWorker(rateLimited(), failingSecondCommit)
                        .process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1), TriageWorker.RATE_LIMIT_DELIVERIES))
                .hasMessage("commit failed"));

        assertThat(lines).noneMatch(line -> line.getFormattedMessage().startsWith("triage.fallback "));
        assertThat(lines).noneMatch(line -> line.getFormattedMessage().startsWith("triage.completed "));
    }

    @Test
    void theSecondDeliveryIsTheLastRateLimitRetrySoAVisitorWaitsAtMostOneRedelivery() {
        claimableJob();
        when(results.save(any(TriageResult.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkerResult second = worker(rateLimited()).process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1), 2);

        assertThat(TriageWorker.RATE_LIMIT_DELIVERIES).isEqualTo(2).isLessThan(TriageWorker.MAX_DELIVERIES);
        assertThat(second.status()).isEqualTo(WorkerResultStatus.ACK);
        verify(results).save(any(TriageResult.class));
    }

    @Test
    void givesTheProviderTheLambdasRemainingTimeLessTheReserve() {
        claimableJob();
        when(results.save(any(TriageResult.class))).thenAnswer(invocation -> invocation.getArgument(0));
        java.util.concurrent.atomic.AtomicReference<TriageRequest> seen = new java.util.concurrent.atomic.AtomicReference<>();
        TriageProvider capturing = (request, redacted) -> {
            seen.set(request);
            return new MockTriageProvider().analyze(request, redacted);
        };

        worker(capturing).process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1), 1, () -> 50_000L);

        assertThat(seen.get().timeBudget().get()).isEqualTo(Duration.ofSeconds(50).minus(TriageWorker.POST_CALL_RESERVE));
    }

    @Test
    void anUnknownRemainingTimeLeavesTheProviderItsOwnBudget() {
        claimableJob();
        when(results.save(any(TriageResult.class))).thenAnswer(invocation -> invocation.getArgument(0));
        java.util.concurrent.atomic.AtomicReference<TriageRequest> seen = new java.util.concurrent.atomic.AtomicReference<>();
        TriageProvider capturing = (request, redacted) -> {
            seen.set(request);
            return new MockTriageProvider().analyze(request, redacted);
        };

        worker(capturing).process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1), 1);

        assertThat(seen.get().timeBudget()).isNull();
    }

    @Test
    void skipsProviderAndBudgetWhenRemainingLambdaTimeIsBelowOneUsefulAttempt() {
        TriageJob job = claimableJob();
        java.util.concurrent.atomic.AtomicInteger providerCalls = new java.util.concurrent.atomic.AtomicInteger();
        TriageProvider counting = (request, redacted) -> {
            providerCalls.incrementAndGet();
            return new MockTriageProvider().analyze(request, redacted);
        };

        // Delivery 1 with 10 s left (below 14 s reserve) ends as retryable without calling the provider.
        WorkerResult first = worker(counting).process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1),
                1, () -> 10_000L);
        assertThat(first.status()).isEqualTo(WorkerResultStatus.RETRY);
        assertThat(first.errorCode()).isEqualTo("TIME_BUDGET_EXHAUSTED");
        assertThat(job.getStatus()).isEqualTo(TriageJobStatus.RETRYABLE_FAILURE);
        assertThat(providerCalls).hasValue(0);

        // Delivery 2 with 16.999 s left (2.999 s < 3 s connect + min first timeout) falls back to rules.
        when(results.save(any(TriageResult.class))).thenAnswer(invocation -> invocation.getArgument(0));
        List<ILoggingEvent> lines = captureWorkerLog(() -> {
            WorkerResult second = worker(counting).process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1),
                    TriageWorker.RATE_LIMIT_DELIVERIES, () -> 16_999L);
            assertThat(second.status()).isEqualTo(WorkerResultStatus.ACK);
        });
        assertThat(providerCalls).hasValue(0);
        verify(results).save(any(TriageResult.class));
        assertThat(lines).anyMatch(line -> line.getFormattedMessage().startsWith("triage.fallback ")
                && line.getFormattedMessage().contains("errorCode=TIME_BUDGET_EXHAUSTED")
                && line.getFormattedMessage().contains("reason=PROVIDER_UNAVAILABLE"));
    }

    @Test
    void deductsBudgetLookupDatabaseStallFromTheProvidersCallBudget() throws Exception {
        claimableJob();
        java.util.concurrent.atomic.AtomicLong remaining = new java.util.concurrent.atomic.AtomicLong(58_000L);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/groq", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("retry-after", "10");
            byte[] body = "rate limited".getBytes();
            exchange.sendResponseHeaders(429, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            java.util.List<Long> sleeps = new java.util.ArrayList<>();
            GroqTriageProvider groq = new GroqTriageProvider(
                    java.net.URI.create("http://localhost:" + server.getAddress().getPort() + "/groq"),
                    "key", "llama-3.1-8b-instant", java.net.http.HttpClient.newHttpClient(), new ObjectMapper(),
                    Duration.ofSeconds(20), () -> 0, GroqTriageProvider.CALL_BUDGET, () -> 0L, sleeps::add);
            com.harishdarko.caselens.security.AiCallBudget budget = new com.harishdarko.caselens.security.AiCallBudget(
                    new com.harishdarko.caselens.security.RateLimitService(clock, 30, Duration.ofMinutes(1)), clock, 10, 5);
            BudgetedTriageProvider budgeted = new BudgetedTriageProvider(groq, budget, id -> {
                // Simulate a 30 s Neon stall inside BudgetedTriageProvider: remaining drops from 58 s to 28 s
                // (call budget 14 s instead of 44 s). A 10 s wait needs 10 + 1 + 2 + 5 = 18 s > 14 s.
                remaining.addAndGet(-30_000L);
                return Optional.of("client-a");
            });

            WorkerResult result = worker(budgeted).process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1),
                    1, remaining::get);

            assertThat(result.status()).isEqualTo(WorkerResultStatus.RETRY);
            assertThat(sleeps).as("must not sleep 10 s when only 14 s remain after DB stall").isEmpty();
            assertThat(calls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void writesFailureMetricsAndLogsOnlyAfterTheFailureTransactionCommits() {
        claimableJob();
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        TriageMetrics metrics = new TriageMetrics(registry);
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        TransactionOperations committing = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                T result = action.doInTransaction(null);
                order.add("commit:" + (int) registry.counter("caselens.triage.retryable.failure").count());
                return result;
            }
        };
        Logger logger = (Logger) LoggerFactory.getLogger(TriageWorker.class);
        AppenderBase<ILoggingEvent> live = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getFormattedMessage().startsWith("triage.retryable_failure ")) order.add("retryable_log");
            }
        };
        live.start();
        logger.addAppender(live);
        try {
            TriageWorker worker = new TriageWorker(jobs, tickets, results, null, transientEngine(rateLimited()),
                    new ObjectMapper(), clock, metrics, new DemoFailurePolicy(false),
                    workspace -> PackRules.evCharging(), committing);
            worker.process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1), 1);
        } finally {
            logger.detachAppender(live);
        }

        assertThat(order).containsExactly("commit:0", "commit:0", "retryable_log");
        assertThat(registry.counter("caselens.triage.retryable.failure").count()).isEqualTo(1.0);
    }

    @Test
    void writesNoFailureMetricsOrLogsWhenTheFailureTransactionFailsToCommit() {
        claimableJob();
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        TriageMetrics metrics = new TriageMetrics(registry);
        java.util.concurrent.atomic.AtomicInteger transactionsRun = new java.util.concurrent.atomic.AtomicInteger();
        TransactionOperations failingSecondCommit = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                T result = action.doInTransaction(null);
                if (transactionsRun.incrementAndGet() == 2) throw new IllegalStateException("commit failed");
                return result;
            }
        };

        List<ILoggingEvent> lines = captureWorkerLog(() -> org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new TriageWorker(jobs, tickets, results, null, transientEngine(rateLimited()),
                        new ObjectMapper(), clock, metrics, new DemoFailurePolicy(false),
                        workspace -> PackRules.evCharging(), failingSecondCommit)
                        .process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1), 1))
                .hasMessage("commit failed"));

        assertThat(lines).noneMatch(line -> line.getFormattedMessage().startsWith("triage.retryable_failure "));
        assertThat(registry.counter("caselens.triage.retryable.failure").count()).isEqualTo(0.0);
    }

    @Test
    void writesClaimMetricsAndLogsOnlyAfterTheClaimTransactionCommits() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, clock.instant());
        when(jobs.findByEventId(eventId)).thenReturn(Optional.of(job));
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        TriageMetrics metrics = new TriageMetrics(registry);
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        TransactionOperations committing = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                T result = action.doInTransaction(null);
                order.add("commit:" + (int) registry.counter("caselens.triage.terminal.failure").count());
                return result;
            }
        };
        Logger logger = (Logger) LoggerFactory.getLogger(TriageWorker.class);
        AppenderBase<ILoggingEvent> live = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getFormattedMessage().startsWith("triage.invalid_event ")) order.add("invalid_event_log");
            }
        };
        live.start();
        logger.addAppender(live);
        try {
            new TriageWorker(jobs, tickets, results, null, transientEngine(new MockTriageProvider()),
                    new ObjectMapper(), clock, metrics, new DemoFailurePolicy(false),
                    workspace -> PackRules.evCharging(), committing)
                    .process(new QueueMessage(eventId, UUID.randomUUID(), ticketId, "corr", 1), 1);
        } finally {
            logger.detachAppender(live);
        }

        assertThat(order).containsExactly("commit:0", "invalid_event_log");
        assertThat(registry.counter("caselens.triage.terminal.failure").count()).isEqualTo(1.0);
    }

    @Test
    void writesNoClaimMetricsOrLogsWhenTheClaimTransactionFailsToCommit() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, clock.instant());
        when(jobs.findByEventId(eventId)).thenReturn(Optional.of(job));
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        TriageMetrics metrics = new TriageMetrics(registry);
        TransactionOperations failingCommit = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                action.doInTransaction(null);
                throw new IllegalStateException("claim commit failed");
            }
        };

        List<ILoggingEvent> lines = captureWorkerLog(() -> org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new TriageWorker(jobs, tickets, results, null, transientEngine(new MockTriageProvider()),
                        new ObjectMapper(), clock, metrics, new DemoFailurePolicy(false),
                        workspace -> PackRules.evCharging(), failingCommit)
                        .process(new QueueMessage(eventId, UUID.randomUUID(), ticketId, "corr", 1), 1))
                .hasMessage("claim commit failed"));

        assertThat(lines).noneMatch(line -> line.getFormattedMessage().startsWith("triage.invalid_event "));
        assertThat(registry.counter("caselens.triage.terminal.failure").count()).isEqualTo(0.0);
    }

    @Test
    void aRateLimitIsJudgedAgainstWhenThisDeliveryWasReceivedNotAFixedWait() throws Exception {
        // The worker's clock, advanced by the fake provider to say how long after receipt the 429 came.
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(clock.instant());
        Clock moving = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        java.util.concurrent.atomic.AtomicLong secondsBeforeAnswer = new java.util.concurrent.atomic.AtomicLong();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/groq", exchange -> {
            now.updateAndGet(instant -> instant.plusSeconds(secondsBeforeAnswer.get()));
            exchange.getResponseHeaders().add("retry-after", "85");
            byte[] body = "rate limited".getBytes();
            exchange.sendResponseHeaders(429, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            GroqTriageProvider groq = new GroqTriageProvider(
                    java.net.URI.create("http://localhost:" + server.getAddress().getPort() + "/groq"),
                    "key", "llama-3.1-8b-instant", java.net.http.HttpClient.newHttpClient(), new ObjectMapper(),
                    Duration.ofSeconds(20), () -> 0, GroqTriageProvider.CALL_BUDGET, () -> 0L, millis -> { });
            TriageWorker worker = new TriageWorker(jobs, tickets, results, transientEngine(groq), new ObjectMapper(), moving);

            // 429 at 1 s after receipt: the limit clears at 86 s, before the redelivery at 90 s -> leave it for SQS.
            claimableJob();
            secondsBeforeAnswer.set(1);
            WorkerResult fast = worker.process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1), 1, () -> 59_000L);
            assertThat(fast.status()).isEqualTo(WorkerResultStatus.RETRY);

            // 429 at 43 s: it clears at 128 s, after the latest moment (127 s) the redelivery's call could wait for -> rules at once.
            claimableJob();
            when(results.save(any(TriageResult.class))).thenAnswer(invocation -> invocation.getArgument(0));
            now.set(clock.instant());
            secondsBeforeAnswer.set(43);
            WorkerResult slow = worker.process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1), 1, () -> 59_000L);
            assertThat(slow.status()).isEqualTo(WorkerResultStatus.ACK);
            verify(results).save(any(TriageResult.class));
        } finally {
            server.stop(0);
        }
    }

    private TriageJob claimableJob() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, clock.instant());
        when(jobs.findByEventId(eventId)).thenReturn(Optional.of(job));
        when(jobs.claimForProcessing(eq(eventId), any(Instant.class))).thenAnswer(invocation -> {
            job.claim(invocation.getArgument(1));
            return 1;
        });
        when(tickets.findByIdAndWorkspaceId(ticketId, workspaceId)).thenReturn(Optional.of(ticket()));
        stillOwnsTheClaim();
        return job;
    }

    private TriageProvider rateLimited() {
        return new TriageProvider() {
            @Override
            public ProviderCall analyze(TriageRequest request, RedactedTicket redacted) {
                throw new ProviderCallException("Groq provider request failed", "HTTP_429");
            }

            @Override public String providerName() { return "groq"; }
        };
    }

    private TriageWorker transactionalWorker(TriageProvider provider, TransactionOperations transactions) {
        return new TriageWorker(jobs, tickets, results, null, transientEngine(provider), new ObjectMapper(), clock,
                TriageMetrics.noop(), new DemoFailurePolicy(false), workspace -> PackRules.evCharging(), transactions);
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
