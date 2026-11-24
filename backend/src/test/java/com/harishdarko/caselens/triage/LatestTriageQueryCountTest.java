package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

/**
 * The ticket list loads each ticket's latest result and latest review for the whole page at once (not one query per
 * ticket, CL-20): the number of SQL statements is the same for 3 tickets as for 12.
 */
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.datasource.url=jdbc:h2:mem:latest-triage-query-count;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "spring.jpa.properties.hibernate.generate_statistics=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LatestTriageQueryCountTest {
    private static final Instant CAPTURED = Instant.parse("2026-11-24T01:00:00Z");
    /** Newest results, reviews, the workspace's industry, and for rules fallbacks their jobs and model calls. */
    private static final int MAX_STATEMENTS = 5;

    @Autowired LatestTriageReader reader;
    @Autowired TriageResultRepository results;
    @Autowired TriageFeedbackRepository feedback;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired ObjectMapper mapper;

    @Test
    void theStatementCountDoesNotGrowWithTheNumberOfTickets() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        int[] ticketCounts = {3, 12};
        long[] statements = new long[ticketCounts.length];
        for (int run = 0; run < ticketCounts.length; run++) {
            UUID workspaceId = UUID.randomUUID();
            List<UUID> ticketIds = new ArrayList<>();
            for (int i = 0; i < ticketCounts[run]; i++) {
                UUID ticketId = UUID.randomUUID();
                ticketIds.add(ticketId);
                // An older recorded result, then a newer one for every other ticket: only the newer is the latest.
                TriageResult older = results.save(TriageResult.recorded(workspaceId, decision(ticketId, DecisionSource.AI_VALIDATED),
                        CAPTURED, mapper));
                TriageResult latest = i % 2 == 0 ? results.save(TriageResult.from(UUID.randomUUID(), workspaceId,
                        decision(ticketId, i % 4 == 0 ? DecisionSource.RULES_FALLBACK : DecisionSource.AI_VALIDATED),
                        CAPTURED.plusSeconds(60), mapper)) : older;
                feedback.save(TriageFeedback.submit(UUID.randomUUID(), workspaceId, ticketId, latest.getId(),
                        FeedbackDecision.ACCEPTED, null, null, null, null, "demo-reviewer", CAPTURED.plusSeconds(120)));
            }

            statistics.clear();
            Map<UUID, LatestTriageReader.Latest> latest = reader.forTickets(workspaceId, ticketIds);
            statements[run] = statistics.getPrepareStatementCount();

            assertThat(latest).hasSize(ticketCounts[run]);
            assertThat(latest.values()).allSatisfy(item -> {
                assertThat(item.feedback()).isNotNull();
                assertThat(item.feedback().decision()).isEqualTo(FeedbackDecision.ACCEPTED);
            });
            assertThat(latest.values().stream().filter(item -> !item.result().recorded())).hasSize((ticketCounts[run] + 1) / 2);
            assertThat(statements[run]).as("statements for %d tickets", ticketCounts[run]).isLessThanOrEqualTo(MAX_STATEMENTS);
        }
        assertThat(statements[1]).isEqualTo(statements[0]);
    }

    private TriageDecision decision(UUID ticketId, DecisionSource source) {
        return new TriageDecision(ticketId, Category.of("GENERAL"), Urgency.LOW, SlaRisk.LOW, Sentiment.NEUTRAL,
                "A stored answer for a ticket.", List.of(), List.of(), "The stored explanation is long enough.",
                List.of("Reply to the customer"), "Thank you for writing in.", ReliabilitySignal.MEDIUM, List.of(), 10,
                List.of(), source, "model", "triage-v2");
    }
}
