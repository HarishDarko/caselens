package com.harishdarko.caselens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harishdarko.caselens.demo.DemoWorkspace;
import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketChannel;
import com.harishdarko.caselens.triage.OutboxEvent;
import com.harishdarko.caselens.triage.TriageJob;
import com.harishdarko.caselens.triage.TriageJobRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.jpa.hibernate.ddl-auto=validate"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresOutboxClaimIntegrationTest {
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
    @Autowired TriageJobRepository jobs;

    /**
     * Backstop for replays of a job SQS gave up on: if the old job's message still arrives while the replacement is
     * active, the reclaim UPDATE hits uq_triage_job_active_ticket_version and fails before any provider call (the
     * worker's claim runs before the provider, so the exception aborts that delivery). The worker also skips
     * superseded jobs up front (TriageWorker.claim); this checks the database rule underneath it.
     */
    @Test
    @Transactional
    void theActiveJobIndexBlocksReclaimingAJobThatHasAnActiveReplacement() {
        UUID workspaceId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-11T18:00:00Z");
        entityManager.persist(new DemoWorkspace(workspaceId, now, now.plusSeconds(86400)));
        entityManager.persist(Ticket.create(ticketId, workspaceId, "Payment issue",
                "Payment was captured but the session never started for this ticket.", TicketChannel.WEB, null, now));
        OutboxEvent oldEvent = OutboxEvent.create(workspaceId, ticketId, "old-message", now);
        entityManager.persist(oldEvent);
        TriageJob old = TriageJob.queued(UUID.randomUUID(), oldEvent.getId(), workspaceId, ticketId, 1, now);
        old.claim(now.plusSeconds(1));
        old.retryableFailure("PROVIDER_TIMEOUT", now.plusSeconds(2));
        entityManager.persist(old);
        OutboxEvent replayEvent = OutboxEvent.create(workspaceId, ticketId, "replay", now.plusSeconds(700));
        entityManager.persist(replayEvent);
        TriageJob replacement = TriageJob.queued(UUID.randomUUID(), replayEvent.getId(), workspaceId, ticketId, 1,
                now.plusSeconds(700));
        replacement.setReplayedFromJobId(old.getId());
        entityManager.persist(replacement);
        entityManager.flush();

        assertThatThrownBy(() -> jobs.claimForProcessing(oldEvent.getId(), now.plusSeconds(900)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void onlyOneConcurrentClaimCanMoveAnOutboxJobIntoProcessing() {
        UUID workspaceId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-11T18:00:00Z");
        entityManager.persist(new DemoWorkspace(workspaceId, now, now.plusSeconds(86400)));
        entityManager.persist(Ticket.create(ticketId, workspaceId, "Payment issue",
                "Payment was captured but the session never started for this ticket.", TicketChannel.WEB, null, now));
        OutboxEvent event = OutboxEvent.create(workspaceId, ticketId, "claim-test", now);
        entityManager.persist(event);
        entityManager.persist(TriageJob.queued(UUID.randomUUID(), event.getId(), workspaceId, ticketId, 1, now));
        entityManager.flush();

        int first = jobs.claimForProcessing(event.getId(), now.plusSeconds(1));
        int second = jobs.claimForProcessing(event.getId(), now.plusSeconds(2));

        assertThat(first).isOne();
        assertThat(second).isZero();
        TriageJob claimed = jobs.findByEventId(event.getId()).orElseThrow();
        assertThat(claimed.getStatus().name()).isEqualTo("PROCESSING");
        assertThat(claimed.getAttemptCount()).isOne();
    }
}
