package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.harishdarko.caselens.demo.DemoWorkspace;
import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketRepository;
import com.harishdarko.caselens.ticket.TicketService;
import com.harishdarko.caselens.ticket.TicketStatus;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Recorded results on the real schema (Flyway migrations, Hibernate validate): seeding inserts exactly the three
 * recorded rows without an event id, and nothing else; a reset replaces them; the evaluation measures them against the
 * benchmark ground truth without counting them as live runs; and the latest-result query picks the live result that
 * follows a recorded one.
 */
@Testcontainers
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.jpa.hibernate.ddl-auto=validate"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresRecordedSeedingIntegrationTest {
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
    @Autowired TicketService ticketService;
    @Autowired TicketRepository tickets;
    @Autowired TriageResultRepository results;
    @Autowired OutboxEventRepository events;
    @Autowired EvaluationService evaluation;
    @Autowired LatestTriageReader reader;
    @Autowired JdbcTemplate jdbc;
    @Autowired tools.jackson.databind.ObjectMapper mapper;

    @Test
    void everyIndustrySeedsEightTicketsWithThreeRecordedResultsAndNothingElse() {
        for (String industry : List.of("ev-charging", "saas-support", "e-commerce")) {
            UUID workspaceId = workspace(industry);

            assertThat(ticketService.reset(workspaceId)).isEqualTo(8);

            assertThat(count("select count(*) from tickets where workspace_id = ?", workspaceId)).as(industry).isEqualTo(8);
            assertThat(count("select count(*) from tickets where workspace_id = ? and status = 'COMPLETED'", workspaceId)).isEqualTo(3);
            assertThat(count("select count(*) from tickets where workspace_id = ? and status = 'NEW'", workspaceId)).isEqualTo(5);
            assertThat(count("select count(*) from triage_result where workspace_id = ? and recorded and event_id is null",
                    workspaceId)).isEqualTo(3);
            assertThat(count("select count(*) from triage_result where workspace_id = ?", workspaceId)).isEqualTo(3);
            assertThat(count("select count(*) from outbox_event where workspace_id = ?", workspaceId)).isZero();
            assertThat(count("select count(*) from triage_job where workspace_id = ?", workspaceId)).isZero();
            assertThat(count("select count(*) from triage_attempt a join triage_job j on j.id = a.job_id where j.workspace_id = ?",
                    workspaceId)).isZero();
            assertThat(count("select count(*) from model_invocations m join tickets t on t.id = m.ticket_id where t.workspace_id = ?",
                    workspaceId)).isZero();
        }
    }

    @Test
    void aSecondResetReplacesTheRecordedResultsInsteadOfAddingToThem() {
        UUID workspaceId = workspace("saas-support");
        ticketService.reset(workspaceId);

        ticketService.reset(workspaceId);

        assertThat(count("select count(*) from triage_result where workspace_id = ?", workspaceId)).isEqualTo(3);
        assertThat(count("select count(*) from tickets where workspace_id = ?", workspaceId)).isEqualTo(8);
    }

    @Test
    void theEvaluationMeasuresRecordedResultsAgainstTheBenchmarkWithoutCountingThemAsLive() {
        UUID workspaceId = workspace("ev-charging");
        ticketService.reset(workspaceId);

        EvaluationSummary summary = evaluation.summarize(workspaceId);

        assertThat(summary.recordedResults()).isEqualTo(3);
        assertThat(summary.triageResults()).isZero();
        assertThat(summary.evaluatedResults()).isEqualTo(3);
        assertThat(summary.agreementRate()).isNotNull();
        assertThat(summary.medianLatencyMs()).isNull();
        assertThat(summary.p95LatencyMs()).isNull();
        assertThat(summary.failureRate()).isNull();
        assertThat(summary.providerUsage()).isEmpty();
        assertThat(summary.recentEvents()).isEmpty();
        assertThat(summary.acceptanceRate()).isNull();
    }

    @Test
    void aLiveResultAfterARecordedOneBecomesTheLatestAndStartsUnreviewed() {
        UUID workspaceId = workspace("e-commerce");
        ticketService.reset(workspaceId);
        List<Ticket> seeded = tickets.findAll().stream().filter(ticket -> ticket.getWorkspaceId().equals(workspaceId)
                && ticket.getStatus() == TicketStatus.COMPLETED).toList();
        Ticket ticket = seeded.get(0);
        Map<UUID, LatestTriageReader.Latest> before = reader.forTickets(workspaceId, List.of(ticket.getId()));
        assertThat(before.get(ticket.getId()).result().recorded()).isTrue();
        UUID liveEvent = transactions.execute(status -> events.save(
                OutboxEvent.create(workspaceId, ticket.getId(), "pr-c-test", Instant.now())).getId());
        TriageDecision decision = new TriageDecision(ticket.getId(), Category.of("GENERAL"), Urgency.LOW, SlaRisk.LOW,
                Sentiment.NEUTRAL, "A live answer to the re-run ticket.", List.of(), List.of(),
                "A live explanation that is long enough to be valid.", List.of("Reply"), "Thank you for your message.",
                ReliabilitySignal.MEDIUM, List.of(), 10, List.of(), DecisionSource.AI_VALIDATED, "live-model", "triage-v2");
        transactions.executeWithoutResult(status ->
                results.save(TriageResult.from(liveEvent, workspaceId, decision, Instant.now(), mapper)));

        LatestTriageReader.Latest after = reader.forTickets(workspaceId, List.of(ticket.getId())).get(ticket.getId());

        assertThat(after.result().recorded()).isFalse();
        assertThat(after.result().modelVersion()).isEqualTo("live-model");
        assertThat(after.feedback()).isNull();
        EvaluationSummary summary = evaluation.summarize(workspaceId);
        assertThat(summary.triageResults()).isEqualTo(1);
        assertThat(summary.recordedResults()).isEqualTo(3);
    }

    private long count(String sql, UUID workspaceId) {
        return jdbc.queryForObject(sql, Long.class, workspaceId);
    }

    private UUID workspace(String industry) {
        UUID workspaceId = UUID.randomUUID();
        Instant now = Instant.now();
        transactions.executeWithoutResult(status ->
                entityManager.persist(new DemoWorkspace(workspaceId, now, now.plus(Duration.ofDays(1)), industry, null)));
        return workspaceId;
    }
}
