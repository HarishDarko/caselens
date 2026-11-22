package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harishdarko.caselens.demo.DemoWorkspace;
import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketChannel;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CL2-03: a job left in QUEUED or PROCESSING after its message went to the DLQ can be replayed once the redelivery
 * window has passed. Runs on Postgres so the conditional close, the active-job unique index and the claim are real.
 */
@Testcontainers
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.jpa.hibernate.ddl-auto=validate"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresStrandedJobRecoveryIntegrationTest {
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
    @Autowired TriageAttemptRepository attempts;
    @Autowired FailureRecoveryService recovery;

    @Test
    void replaysAStrandedQueuedJobOnceAndAcksTheLateOriginalMessage() {
        Instant now = Instant.now();
        Seed seed = seed(now.minus(Duration.ofMinutes(20)), null);

        assertThat(recovery.failures(seed.workspaceId(), 0, 20).map(TriageJobSnapshot::jobId).getContent())
                .containsExactly(seed.jobId());
        TriageJobSnapshot replacement = recovery.retry(seed.workspaceId(), seed.jobId(), "corr-stranded");

        assertThat(replacement.jobId()).isNotEqualTo(seed.jobId());
        assertThat(replacement.status()).isEqualTo(TriageJobStatus.QUEUED);
        TriageJob closed = jobs.findById(seed.jobId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(TriageJobStatus.TERMINAL_FAILURE);
        assertThat(closed.getLastErrorCode()).isEqualTo("SQS_DELIVERY_EXHAUSTED");
        assertThat(jobs.findById(replacement.jobId()).orElseThrow().getReplayedFromJobId()).isEqualTo(seed.jobId());
        // A late or redriven original message cannot claim the closed job, so the worker acks it as a duplicate.
        Integer claimed = transactions.execute(status -> jobs.claimForProcessing(seed.eventId(), Instant.now()));
        assertThat(claimed).isZero();
        // A double click returns the replacement, and the closed job leaves the recovery list.
        assertThat(recovery.retry(seed.workspaceId(), seed.jobId(), "corr-again").jobId()).isEqualTo(replacement.jobId());
        assertThat(recovery.failures(seed.workspaceId(), 0, 20).getContent()).isEmpty();
    }

    @Test
    void replaysAStrandedProcessingJobAndClosesItsOpenAttempt() {
        Instant now = Instant.now();
        Seed seed = seed(now.minus(Duration.ofMinutes(30)), now.minus(Duration.ofMinutes(20)));

        TriageJobSnapshot replacement = recovery.retry(seed.workspaceId(), seed.jobId(), "corr-processing");

        assertThat(replacement.jobId()).isNotEqualTo(seed.jobId());
        assertThat(jobs.findById(seed.jobId()).orElseThrow().getStatus()).isEqualTo(TriageJobStatus.TERMINAL_FAILURE);
        List<TriageAttempt> timeline = attempts.findByJobIdOrderByAttemptNumberAsc(seed.jobId());
        assertThat(timeline).singleElement().satisfies(attempt -> {
            assertThat(attempt.getStatus()).isEqualTo(TriageAttemptStatus.TERMINAL_FAILURE);
            assertThat(attempt.getErrorCode()).isEqualTo("SQS_DELIVERY_EXHAUSTED");
        });
    }

    @Test
    void leavesAJobThatSqsStillOwnsAlone() {
        Instant now = Instant.now();
        Seed queued = seed(now.minus(Duration.ofMinutes(2)), null);
        Seed processing = seed(now.minus(Duration.ofMinutes(3)), now.minus(Duration.ofSeconds(30)));

        assertThat(recovery.failures(queued.workspaceId(), 0, 20).getContent()).isEmpty();
        assertThatThrownBy(() -> recovery.retry(queued.workspaceId(), queued.jobId(), "corr"))
                .hasMessage("Active triage jobs remain owned by SQS");
        assertThatThrownBy(() -> recovery.retry(processing.workspaceId(), processing.jobId(), "corr"))
                .hasMessage("Active triage jobs remain owned by SQS");
        // The conditional close is the backstop under the ticket lock: it changes nothing for a job still in play.
        Instant later = Instant.now();
        Integer closed = transactions.execute(status -> jobs.closeStranded(processing.jobId(), "SQS_DELIVERY_EXHAUSTED",
                later, later.minus(FailureRecoveryService.SQS_REDELIVERY_WINDOW), later.minus(TriageJobRepository.CLAIM_LEASE)));
        assertThat(closed).isZero();
        assertThat(jobs.findById(processing.jobId()).orElseThrow().getStatus()).isEqualTo(TriageJobStatus.PROCESSING);
    }

    /** A workspace, a ticket and one job created at createdAt, claimed at claimedAt (with an open attempt) when set. */
    private Seed seed(Instant createdAt, Instant claimedAt) {
        UUID workspaceId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        return transactions.execute(status -> {
            entityManager.persist(new DemoWorkspace(workspaceId, createdAt, createdAt.plus(Duration.ofDays(1))));
            entityManager.persist(Ticket.create(ticketId, workspaceId, "Charger offline",
                    "Every charger at the site shows offline since this morning.", TicketChannel.WEB, null, createdAt));
            OutboxEvent event = OutboxEvent.create(workspaceId, ticketId, "stranded", createdAt);
            entityManager.persist(event);
            TriageJob job = TriageJob.queued(UUID.randomUUID(), event.getId(), workspaceId, ticketId, 1, createdAt);
            if (claimedAt != null) job.claim(claimedAt);
            entityManager.persist(job);
            if (claimedAt != null) entityManager.persist(TriageAttempt.processing(job.getId(), 1, claimedAt));
            return new Seed(workspaceId, job.getId(), event.getId());
        });
    }

    private record Seed(UUID workspaceId, UUID jobId, UUID eventId) {}
}
