package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketChannel;
import com.harishdarko.caselens.ticket.TicketRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.datasource.url=jdbc:h2:mem:evaluation-query-count;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "spring.jpa.properties.hibernate.generate_statistics=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EvaluationQueryCountTest {
    private static final Instant CREATED = Instant.parse("2026-11-09T15:00:00Z");
    private static final String SCENARIO = "query-count-scenario";
    private static final int MAX_STATEMENTS = 6;

    @Autowired EvaluationService evaluation;
    @Autowired TicketRepository tickets;
    @Autowired TriageResultRepository results;
    @Autowired TriageJobRepository jobs;
    @Autowired EvaluationGroundTruthRepository groundTruth;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired ObjectMapper objectMapper;

    @Test
    void summaryUsesAFixedNumberOfStatementsWhateverTheNumberOfResults() {
        groundTruth.save(new EvaluationGroundTruth(SCENARIO, Category.of("CHARGING_SESSION"), Urgency.MEDIUM));
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        int[] resultCounts = {9, 27};
        long[] statements = new long[resultCounts.length];
        List<EvaluationSummary> summaries = new ArrayList<>();
        for (int run = 0; run < resultCounts.length; run++) {
            UUID workspaceId = UUID.randomUUID();
            List<Ticket> seeded = new ArrayList<>();
            for (int i = 0; i < 9; i++) seeded.add(tickets.save(ticket(workspaceId)));
            for (int i = 0; i < resultCounts[run]; i++) {
                saveResult(workspaceId, seeded.get(i % seeded.size()).getId(), CREATED.plusSeconds(i));
            }

            statistics.clear();
            summaries.add(evaluation.summarize(workspaceId));
            statements[run] = statistics.getPrepareStatementCount();
            System.out.println("EVALUATION-QUERY-COUNT results=" + resultCounts[run] + " statements=" + statements[run]);
        }

        for (int run = 0; run < resultCounts.length; run++) {
            int resultCount = resultCounts[run];
            EvaluationSummary summary = summaries.get(run);
            assertThat(statements[run]).as("SQL statements in summarize() for %d results", resultCount)
                    .isLessThanOrEqualTo(MAX_STATEMENTS);
            assertThat(summary.triageResults()).isEqualTo(resultCount);
            assertThat(summary.evaluatedResults()).isEqualTo(resultCount);
            assertThat(summary.categoryAgreementRate()).isEqualTo(1.0);
            assertThat(summary.recentEvents()).hasSize(Math.min(20, resultCount))
                    .allSatisfy(event -> assertThat(event.evaluated()).isTrue());
        }
    }

    @Test
    void summaryIgnoresATicketThatBelongsToAnotherWorkspace() {
        groundTruth.save(new EvaluationGroundTruth(SCENARIO, Category.of("CHARGING_SESSION"), Urgency.MEDIUM));
        UUID workspaceId = UUID.randomUUID();
        Ticket foreign = tickets.save(ticket(UUID.randomUUID()));
        saveResult(workspaceId, foreign.getId(), CREATED);

        EvaluationSummary summary = evaluation.summarize(workspaceId);

        assertThat(summary.triageResults()).isEqualTo(1);
        assertThat(summary.evaluatedResults()).isZero();
        assertThat(summary.recentEvents()).singleElement().satisfies(event -> assertThat(event.evaluated()).isFalse());
    }

    private Ticket ticket(UUID workspaceId) {
        return Ticket.create(UUID.randomUUID(), workspaceId, "Synthetic session issue",
                "Synthetic ticket message that is long enough for evaluation.", TicketChannel.WEB, SCENARIO, CREATED);
    }

    private void saveResult(UUID workspaceId, UUID ticketId, Instant at) {
        UUID eventId = UUID.randomUUID();
        results.save(TriageResult.from(eventId, workspaceId, new TriageDecision(ticketId,
                Category.of("CHARGING_SESSION"), Urgency.MEDIUM, SlaRisk.MEDIUM, Sentiment.NEGATIVE,
                "Charging did not start", List.of(), List.of(), "The session did not start.",
                List.of("Inspect the charger"), "We are reviewing the session.", ReliabilitySignal.HIGH,
                List.of(), 55, List.of(), DecisionSource.AI_VALIDATED, "mock-v1", "triage-v1"), at.plusSeconds(2), objectMapper));
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, workspaceId, ticketId, 1, at);
        job.claim(at.plusSeconds(1));
        job.complete(at.plusSeconds(2));
        jobs.save(job);
    }
}
