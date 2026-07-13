package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        when(results.save(any(TriageResult.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkerResult result = worker(new MockTriageProvider()).process(
                new QueueMessage(eventId, workspaceId, ticketId, "corr", 1));

        assertThat(result.status()).isEqualTo(WorkerResultStatus.ACK);
        assertThat(job.getStatus()).isEqualTo(TriageJobStatus.COMPLETED);
        assertThat(ticket.getStatus()).isEqualTo(com.harishdarko.caselens.ticket.TicketStatus.COMPLETED);
        verify(results).save(any(TriageResult.class));
    }

    @Test
    void acknowledgesWhenAnotherWorkerWinsTheAtomicClaim() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, clock.instant());
        TriageProvider provider = org.mockito.Mockito.mock(TriageProvider.class);
        when(jobs.findByEventId(eventId)).thenReturn(Optional.of(job));
        when(jobs.claimForProcessing(eq(eventId), any(Instant.class))).thenReturn(0);

        WorkerResult result = workerWithEngine(transientEngine(provider)).process(
                new QueueMessage(eventId, workspaceId, ticketId, "corr", 1));

        assertThat(result.status()).isEqualTo(WorkerResultStatus.ACK_DUPLICATE);
        verify(provider, never()).analyze(any(), any());
        verify(tickets, never()).findByIdAndWorkspaceId(any(), any());
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
