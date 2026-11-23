package com.harishdarko.caselens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V11 adds recorded results (no outbox event, so event_id may be NULL only for them) and the feedback decision
 * (ACCEPTED with no change, or CORRECTED with at least one). It must be safe across the deploy gap in both
 * directions: rows written before it read as live results and corrections, and the previous application version,
 * which knows neither column, can still insert results and feedback after it ran.
 */
@Testcontainers
class RecordedResultsMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("caselens")
            .withUsername("caselens")
            .withPassword("caselens");

    @Test
    void addsRecordedResultsAndFeedbackDecisionsWithoutBreakingRowsOrWritersFromBeforeIt() throws Exception {
        flyway("10").migrate();
        UUID workspace = UUID.randomUUID();
        UUID ticket = UUID.randomUUID();
        UUID oldResult = UUID.randomUUID();
        UUID oldFeedback = UUID.randomUUID();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO demo_workspaces(id, created_at, expires_at) VALUES ('" + workspace
                    + "', now(), now() + interval '24 hours')");
            statement.executeUpdate("INSERT INTO tickets(id, workspace_id, display_id, subject, message, channel, status, "
                    + "created_at, updated_at) VALUES ('" + ticket + "', '" + workspace + "', 'CL-0000A1', 'Synthetic issue', "
                    + "'A synthetic ticket message that passes database validation.', 'WEB', 'COMPLETED', now(), now())");
            statement.executeUpdate(oldResultInsert(workspace, ticket, oldResult, outboxEvent(statement, workspace, ticket)));
            statement.executeUpdate(oldFeedbackInsert(workspace, ticket, oldResult, oldFeedback));
        }

        Flyway latest = flyway(null);
        latest.migrate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("11");

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(single(statement, "SELECT recorded FROM triage_result WHERE id = '" + oldResult + "'")).isEqualTo("f");
            assertThat(single(statement, "SELECT decision FROM triage_feedback WHERE id = '" + oldFeedback + "'"))
                    .isEqualTo("CORRECTED");

            // The previous application version, still serving during the deploy, inserts without the new columns.
            UUID laterResult = UUID.randomUUID();
            statement.executeUpdate(oldResultInsert(workspace, ticket, laterResult, outboxEvent(statement, workspace, ticket)));
            statement.executeUpdate(oldFeedbackInsert(workspace, ticket, laterResult, UUID.randomUUID()));
            assertThat(single(statement, "SELECT count(*) FROM triage_feedback WHERE decision = 'CORRECTED'")).isEqualTo("2");

            // A recorded result has no outbox event; a live one still must have one.
            statement.executeUpdate(resultInsert(workspace, ticket, UUID.randomUUID(), null, true));
            statement.executeUpdate(resultInsert(workspace, ticket, UUID.randomUUID(), null, true));
            assertThatThrownBy(() -> statement.executeUpdate(resultInsert(workspace, ticket, UUID.randomUUID(), null, false)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("chk_triage_result_live_event");

            // ACCEPTED changes nothing and needs no note; CORRECTED changes at least one field, with or without a note.
            statement.executeUpdate(feedbackInsert(workspace, ticket, laterResult, "'ACCEPTED'", "NULL", "NULL"));
            statement.executeUpdate(feedbackInsert(workspace, ticket, laterResult, "'CORRECTED'", "'HIGH'", "NULL"));
            assertThatThrownBy(() -> statement.executeUpdate(feedbackInsert(workspace, ticket, laterResult, "'ACCEPTED'", "'HIGH'", "NULL")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("chk_feedback_decision");
            assertThatThrownBy(() -> statement.executeUpdate(feedbackInsert(workspace, ticket, laterResult, "'CORRECTED'", "NULL", "'note'")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("chk_feedback_decision");
            assertThatThrownBy(() -> statement.executeUpdate(feedbackInsert(workspace, ticket, laterResult, "'MAYBE'", "NULL", "NULL")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("chk_feedback_decision");
        }
    }

    @Test
    void theMigrationFailsFastInsteadOfWaitingBehindALongTransaction() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V11__add_recorded_results_and_feedback_decision.sql"));

        assertThat(sql).contains("SET LOCAL lock_timeout = '5s';");
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) configuration.target(MigrationVersion.fromVersion(target));
        return configuration.load();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static UUID outboxEvent(Statement statement, UUID workspace, UUID ticket) throws SQLException {
        UUID event = UUID.randomUUID();
        statement.executeUpdate("INSERT INTO outbox_event(id, workspace_id, ticket_id, event_type, payload, created_at) VALUES ('"
                + event + "', '" + workspace + "', '" + ticket + "', 'TRIAGE_REQUESTED', '{}', now())");
        return event;
    }

    private static String single(Statement statement, String sql) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private static final String RESULT_COLUMNS = "id, event_id, workspace_id, ticket_id, category, urgency, sla_risk, sentiment, "
            + "summary, evidence_json, policy_ids_json, explanation, recommended_actions_json, suggested_reply, "
            + "reliability_signal, warnings_json, priority_score, applied_rules_json, decision_source, model_version, "
            + "prompt_version, created_at";

    private static String resultValues(UUID workspace, UUID ticket, UUID id, UUID event) {
        return "'" + id + "', " + (event == null ? "NULL" : "'" + event + "'") + ", '" + workspace + "', '" + ticket
                + "', 'BILLING', 'HIGH', 'HIGH', 'NEGATIVE', 'Summary', '[]', '[]', 'Explanation', '[]', 'Reply', 'HIGH', "
                + "'[]', 70, '[]', 'AI_VALIDATED', 'model', 'triage-v2', now()";
    }

    /** The insert the application before V11 makes: no recorded column. */
    private static String oldResultInsert(UUID workspace, UUID ticket, UUID id, UUID event) {
        return "INSERT INTO triage_result(" + RESULT_COLUMNS + ") VALUES (" + resultValues(workspace, ticket, id, event) + ")";
    }

    private static String resultInsert(UUID workspace, UUID ticket, UUID id, UUID event, boolean recorded) {
        return "INSERT INTO triage_result(" + RESULT_COLUMNS + ", recorded) VALUES (" + resultValues(workspace, ticket, id, event)
                + ", " + recorded + ")";
    }

    /** The insert the application before V11 makes: no decision column, and always a note and a correction. */
    private static String oldFeedbackInsert(UUID workspace, UUID ticket, UUID result, UUID id) {
        return "INSERT INTO triage_feedback(id, workspace_id, ticket_id, triage_result_id, corrected_urgency, note, "
                + "submitted_by, created_at) VALUES ('" + id + "', '" + workspace + "', '" + ticket + "', '" + result
                + "', 'HIGH', 'Escalation confirmed', 'demo-reviewer', now())";
    }

    private static String feedbackInsert(UUID workspace, UUID ticket, UUID result, String decision, String urgency, String note) {
        return "INSERT INTO triage_feedback(id, workspace_id, ticket_id, triage_result_id, decision, corrected_urgency, note, "
                + "submitted_by, created_at) VALUES ('" + UUID.randomUUID() + "', '" + workspace + "', '" + ticket + "', '"
                + result + "', " + decision + ", " + urgency + ", " + note + ", 'demo-reviewer', now())";
    }
}
