package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harishdarko.caselens.demo.DemoWorkspace;
import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketChannel;
import com.harishdarko.caselens.ticket.TicketNotFoundException;
import com.harishdarko.caselens.ticket.TicketRepository;
import com.harishdarko.caselens.ticket.TicketService;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Races on Postgres: a feedback submit against a concurrent reset (CL3-03), and client-chosen ids (CL-05 requestId),
 * which are inserted with persist so a collision fails on the primary key and never updates another workspace's row.
 */
@Testcontainers
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.jpa.hibernate.ddl-auto=validate"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresClientIdAndResetIntegrationTest {
    private static final String SCENARIO = "payment-captured-session-not-started";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("caselens")
            .withUsername("caselens")
            .withPassword("caselens");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate transactions;
    @Autowired TicketRepository tickets;
    @Autowired TicketService ticketService;
    @Autowired FeedbackService feedbackService;
    @Autowired TriageFeedbackRepository feedback;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    /**
     * Before the fix the submit read the ticket and result before it waited for the workspace lock, then inserted
     * feedback for a ticket the reset had deleted: a foreign-key failure and a 500. Now it waits first and reads after.
     */
    @Test
    void feedbackWaitingOnAResetFindsTheTicketGoneAndAnswersNotFound() throws Exception {
        UUID workspaceId = workspace();
        Ticket ticket = ticket(workspaceId);
        TriageResult result = result(workspaceId, ticket);
        CountDownLatch resetDone = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Integer> reset = CompletableFuture.supplyAsync(() -> transactions.execute(status -> {
            int seeded = ticketService.reset(workspaceId);
            resetDone.countDown();
            await(release);
            return seeded;
        }));
        await(resetDone);
        CompletableFuture<FeedbackSnapshot> submit = CompletableFuture.supplyAsync(() -> feedbackService.submit(
                workspaceId, ticket.getId(), result.getId(), FeedbackDecision.CORRECTED, null, Urgency.HIGH, null, "Escalation confirmed", null));
        waitForALockWait();
        release.countDown();

        assertThat(reset.get(30, TimeUnit.SECONDS)).isPositive();
        assertThatThrownBy(() -> submit.get(30, TimeUnit.SECONDS)).hasCauseInstanceOf(TicketNotFoundException.class);
        assertThat(feedback.countByWorkspaceId(workspaceId)).isZero();
    }

    /** A new ticket is inserted, never merged: an id another workspace holds fails instead of moving that row. */
    @Test
    void savingANewTicketWithAnIdAnotherWorkspaceHoldsFailsInsteadOfUpdatingIt() {
        UUID owner = workspace();
        UUID other = workspace();
        Ticket held = ticket(owner);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> tickets.saveAndFlush(Ticket.create(
                held.getId(), other, "Another subject", "Another message that is long enough.", TicketChannel.WEB, null,
                Instant.now()))))
                .isInstanceOf(DataIntegrityViolationException.class);
        Ticket stored = tickets.findById(held.getId()).orElseThrow();
        assertThat(stored.getWorkspaceId()).isEqualTo(owner);
        assertThat(stored.getSubject()).isEqualTo(held.getSubject());
    }

    /** Two workspaces race on one requestId: the second insert waits on the first, then fails on the key: a 409. */
    @Test
    void twoWorkspacesRacingOnOneRequestIdGiveTheSecondAConflict() throws Exception {
        UUID first = workspace();
        UUID second = workspace();
        UUID requestId = UUID.randomUUID();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Ticket> winner = CompletableFuture.supplyAsync(() -> transactions.execute(status -> {
            Ticket created = ticketService.createScenario(first, SCENARIO, requestId);
            inserted.countDown();
            await(release);
            return created;
        }));
        await(inserted);
        CompletableFuture<Ticket> loser = CompletableFuture.supplyAsync(
                () -> ticketService.createScenario(second, SCENARIO, requestId));
        waitForALockWait();
        release.countDown();

        assertThat(winner.get(30, TimeUnit.SECONDS).getId()).isEqualTo(requestId);
        assertThatThrownBy(() -> loser.get(30, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .cause().hasMessage("The request id is already in use");
        assertThat(tickets.findById(requestId).orElseThrow().getWorkspaceId()).isEqualTo(first);
        assertThat(tickets.countByWorkspaceId(second)).isZero();
    }

    private UUID workspace() {
        UUID workspaceId = UUID.randomUUID();
        Instant now = Instant.now();
        transactions.executeWithoutResult(status ->
                entityManager.persist(new DemoWorkspace(workspaceId, now, now.plus(Duration.ofDays(1)))));
        return workspaceId;
    }

    private Ticket ticket(UUID workspaceId) {
        return transactions.execute(status -> tickets.save(Ticket.create(UUID.randomUUID(), workspaceId,
                "Charger offline", "Every charger at the site shows offline since this morning.", TicketChannel.WEB,
                SCENARIO, Instant.now())));
    }

    private TriageResult result(UUID workspaceId, Ticket ticket) {
        return transactions.execute(status -> {
            OutboxEvent event = OutboxEvent.create(workspaceId, ticket.getId(), "reset-race", Instant.now());
            entityManager.persist(event);
            TriageResult result = TriageResult.from(event.getId(), workspaceId, new TriageDecision(ticket.getId(),
                    Category.of("CHARGING_SESSION"), Urgency.MEDIUM, SlaRisk.MEDIUM, Sentiment.NEGATIVE,
                    "Charging did not start", List.of(), List.of(), "The session did not start.",
                    List.of("Inspect the charger"), "We are reviewing the session.", ReliabilitySignal.HIGH,
                    List.of(), 55, List.of(), DecisionSource.AI_VALIDATED, "mock-v1", "triage-v1"),
                    Instant.now(), objectMapper);
            entityManager.persist(result);
            return result;
        });
    }

    private void waitForALockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(25);
        }
        throw new AssertionError("No transaction started waiting for a lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for the other transaction");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
