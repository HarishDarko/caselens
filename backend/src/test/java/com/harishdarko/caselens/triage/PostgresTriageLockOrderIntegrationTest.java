package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harishdarko.caselens.demo.DemoWorkspace;
import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketChannel;
import com.harishdarko.caselens.ticket.TicketRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CL3-01: every writer of a ticket's triage jobs takes the ticket row lock first (request, replay, and the worker's
 * claim and final writes), so a reviewer replay and a redriven SQS message run one after the other instead of
 * deadlocking, and the replay decides on the job as it is once it holds the lock, not on a copy read before.
 */
@Testcontainers
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.jpa.hibernate.ddl-auto=validate"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresTriageLockOrderIntegrationTest {
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
    @Autowired TriageJobRepository jobs;
    @Autowired TicketRepository tickets;
    @Autowired FailureRecoveryService recovery;
    @Autowired TriageWorker worker;
    @Autowired JdbcTemplate jdbc;

    /**
     * Before the fix the worker updated triage_job and then tickets, while the replay locked tickets and then updated
     * triage_job, so the two deadlocked (40P01). Now the worker waits for the ticket lock before it touches the job.
     */
    @Test
    void aRedrivenMessageWaitsForAReplayThatHoldsTheTicketInsteadOfDeadlocking() throws Exception {
        Instant old = Instant.now().minus(Duration.ofMinutes(20));
        Seed seed = seed(old, TriageJobStatus.QUEUED);
        CountDownLatch ticketLocked = new CountDownLatch(1);
        CountDownLatch workerWaiting = new CountDownLatch(1);

        // The replay's order: the ticket lock, then the conditional close of the stranded job.
        CompletableFuture<Integer> replay = CompletableFuture.supplyAsync(() -> transactions.execute(status -> {
            tickets.findByIdAndWorkspaceIdForUpdate(seed.ticketId(), seed.workspaceId()).orElseThrow();
            ticketLocked.countDown();
            await(workerWaiting);
            Instant now = Instant.now();
            return jobs.closeStranded(seed.jobId(), "SQS_DELIVERY_EXHAUSTED", now,
                    now.minus(FailureRecoveryService.SQS_REDELIVERY_WINDOW), now.minus(TriageJobRepository.CLAIM_LEASE));
        }));
        await(ticketLocked);
        CompletableFuture<WorkerResult> delivery = CompletableFuture.supplyAsync(() -> worker.process(
                new QueueMessage(seed.eventId(), seed.workspaceId(), seed.ticketId(), "corr-redrive", 1)));
        waitForALockWait();
        workerWaiting.countDown();

        assertThat(replay.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        // The worker ran after the replay committed, found the job closed and acknowledged the message.
        assertThat(delivery.get(30, TimeUnit.SECONDS).status()).isEqualTo(WorkerResultStatus.ACK_DUPLICATE);
        assertThat(jobs.findById(seed.jobId()).orElseThrow().getStatus()).isEqualTo(TriageJobStatus.TERMINAL_FAILURE);
    }

    /**
     * Before the fix retry() loaded the job before it waited for the ticket lock, so a claim that committed meanwhile
     * was invisible: the replay queued a second active job and failed on the active-job unique index (a 500).
     */
    @Test
    void aRetryThatWaitedForTheTicketSeesTheClaimThatCommittedMeanwhile() throws Exception {
        Instant old = Instant.now().minus(Duration.ofMinutes(20));
        Seed seed = seed(old, TriageJobStatus.RETRYABLE_FAILURE);
        CountDownLatch ticketLocked = new CountDownLatch(1);
        CountDownLatch retryWaiting = new CountDownLatch(1);

        // A redriven delivery: the ticket lock, then the claim of the old message.
        CompletableFuture<Integer> claim = CompletableFuture.supplyAsync(() -> transactions.execute(status -> {
            tickets.findByIdAndWorkspaceIdForUpdate(seed.ticketId(), seed.workspaceId()).orElseThrow();
            ticketLocked.countDown();
            await(retryWaiting);
            return jobs.claimForProcessing(seed.eventId(), Instant.now());
        }));
        await(ticketLocked);
        CompletableFuture<TriageJobSnapshot> retry = CompletableFuture.supplyAsync(
                () -> recovery.retry(seed.workspaceId(), seed.jobId(), "corr-retry"));
        waitForALockWait();
        retryWaiting.countDown();

        assertThat(claim.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThatThrownBy(() -> retry.get(30, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .cause().hasMessage("Active triage jobs remain owned by SQS");
        assertThat(jobs.findByWorkspaceId(seed.workspaceId())).singleElement()
                .satisfies(job -> assertThat(job.getStatus()).isEqualTo(TriageJobStatus.PROCESSING));
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

    /** A workspace, a ticket and one published job created at createdAt, left QUEUED or failed retryably. */
    private Seed seed(Instant createdAt, TriageJobStatus status) {
        UUID workspaceId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        return transactions.execute(tx -> {
            entityManager.persist(new DemoWorkspace(workspaceId, createdAt, createdAt.plus(Duration.ofDays(1))));
            entityManager.persist(Ticket.create(ticketId, workspaceId, "Charger offline",
                    "Every charger at the site shows offline since this morning.", TicketChannel.WEB, null, createdAt));
            OutboxEvent event = OutboxEvent.create(workspaceId, ticketId, "lock-order", createdAt);
            event.markPublished(createdAt);
            entityManager.persist(event);
            TriageJob job = TriageJob.queued(UUID.randomUUID(), event.getId(), workspaceId, ticketId, 1, createdAt);
            if (status == TriageJobStatus.RETRYABLE_FAILURE) {
                job.claim(createdAt);
                job.retryableFailure("PROVIDER_TIMEOUT", createdAt);
            }
            entityManager.persist(job);
            return new Seed(workspaceId, ticketId, job.getId(), event.getId());
        });
    }

    private record Seed(UUID workspaceId, UUID ticketId, UUID jobId, UUID eventId) {}
}
