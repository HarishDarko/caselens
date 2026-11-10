package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketChannel;
import com.harishdarko.caselens.ticket.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PackAwareWorkerTest {
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T05:00:00Z"), ZoneOffset.UTC);

    @Mock TriageJobRepository jobs;
    @Mock TicketRepository tickets;
    @Mock TriageResultRepository results;

    @Test
    void theWorkerTriagesWithThePackOfTheJobsWorkspace() {
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, clock.instant());
        Ticket ticket = Ticket.create(ticketId, workspaceId, "Locked out of my account",
                "I am locked out after the password reset. The login page keeps failing.", TicketChannel.WEB, "test", clock.instant());
        when(jobs.findByEventId(eventId)).thenReturn(Optional.of(job));
        when(jobs.claimForProcessing(eq(eventId), any(Instant.class))).thenAnswer(invocation -> {
            job.claim(invocation.getArgument(1));
            return 1;
        });
        when(tickets.findByIdAndWorkspaceId(ticketId, workspaceId)).thenReturn(Optional.of(ticket));
        when(jobs.holdClaim(eq(eventId), any(Integer.class), any(Instant.class))).thenReturn(1);
        AtomicReference<UUID> resolvedFor = new AtomicReference<>();
        PackResolver packs = id -> {
            resolvedFor.set(id);
            return TestPacks.saas();
        };
        AtomicReference<TriageRequest> seen = new AtomicReference<>();
        TriageProvider failing = (request, redacted) -> {
            seen.set(request);
            throw new ProviderCallException("provider unavailable", "HTTP_500");
        };
        TriageEngine engine = new TriageEngine(new TicketRedactor(), failing, new RulesFallbackProvider(),
                new SemanticValidator(PolicyCatalog.defaultCatalog()), new PriorityScorer(), ignored -> {}, clock);
        AtomicReference<TriageResult> saved = new AtomicReference<>();
        when(results.save(any(TriageResult.class))).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        WorkerResult result = new TriageWorker(jobs, tickets, results, null, engine, new ObjectMapper(), clock,
                TriageMetrics.noop(), new DemoFailurePolicy(false), packs)
                .process(new QueueMessage(eventId, workspaceId, ticketId, "corr", 1));

        assertThat(result.status()).isEqualTo(WorkerResultStatus.ACK);
        assertThat(resolvedFor.get()).isEqualTo(workspaceId);
        assertThat(seen.get().pack().id()).isEqualTo("test-saas");
        assertThat(saved.get().getCategory()).isEqualTo(Category.of("LOGIN"));
    }
}
