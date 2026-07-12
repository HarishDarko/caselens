package com.harishdarko.caselens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import com.harishdarko.caselens.security.RateLimitExceededException;
import com.harishdarko.caselens.security.RateLimitService;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("caselens")
            .withUsername("caselens")
            .withPassword("caselens");

    @Test
    void migratesTheWorkspaceScopedTicketSchemaWithProductionConstraints() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            UUID workspace = UUID.randomUUID();
            statement.executeUpdate("INSERT INTO demo_workspaces(id, created_at, expires_at) VALUES ('" + workspace
                    + "', now(), now() + interval '24 hours')");
            statement.executeUpdate(validTicketInsert(workspace, UUID.randomUUID(), "CL-A1B2C3"));

            assertThatThrownBy(() -> statement.executeUpdate(validTicketInsert(workspace, UUID.randomUUID(), "CL-A1B2C3")))
                    .isInstanceOf(SQLException.class);

            try (ResultSet indexes = statement.executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'tickets' ORDER BY indexname")) {
                List<String> names = new ArrayList<>();
                while (indexes.next()) names.add(indexes.getString(1));
                assertThat(names).contains("idx_tickets_workspace_created", "uq_tickets_workspace_display_id");
            }

            try (ResultSet columns = statement.executeQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'model_invocations'")) {
                List<String> names = new ArrayList<>();
                while (columns.next()) names.add(columns.getString(1));
                assertThat(names).contains("ticket_id", "provider", "model_version", "prompt_version",
                        "started_at", "ended_at", "latency_ms", "status", "sanitized_error_code",
                        "input_tokens", "output_tokens");
                assertThat(names).doesNotContain("raw_ticket", "request_body", "raw_response", "api_key");
            }

            try (ResultSet tables = statement.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {
                List<String> names = new ArrayList<>();
                while (tables.next()) names.add(tables.getString(1));
                assertThat(names).contains("outbox_event", "triage_job", "triage_attempt", "triage_result",
                        "triage_feedback", "evaluation_ground_truth", "rate_limit_buckets");
            }

            try (ResultSet truth = statement.executeQuery("SELECT count(*) FROM evaluation_ground_truth")) {
                assertThat(truth.next()).isTrue();
                assertThat(truth.getInt(1)).isEqualTo(8);
            }

            try (ResultSet indexes = statement.executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'triage_job'")) {
                List<String> names = new ArrayList<>();
                while (indexes.next()) names.add(indexes.getString(1));
                assertThat(names).contains("uq_triage_job_active_ticket_version");
            }
        }
    }

    @Test
    void persistsLambdaRateLimitBucketsAcrossApplicationInstances() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        RateLimitService service = new RateLimitService(
                Clock.fixed(Instant.parse("2040-07-11T12:00:00Z"), ZoneOffset.UTC), 2, Duration.ofMinutes(1),
                new JdbcTemplate(dataSource));

        String key = "198.51.100.20-" + UUID.randomUUID();
        service.acquire(key);
        service.acquire(key);

        assertThatThrownBy(() -> service.acquire(key))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting(exception -> ((RateLimitExceededException) exception).retryAfterSeconds())
                .isEqualTo(60L);
    }

    private String validTicketInsert(UUID workspace, UUID ticket, String displayId) {
        return "INSERT INTO tickets(id, workspace_id, display_id, subject, message, channel, status, created_at, updated_at) "
                + "VALUES ('" + ticket + "', '" + workspace + "', '" + displayId
                + "', 'Synthetic issue', 'A synthetic ticket message that passes database validation.', "
                + "'WEB', 'NEW', now(), now())";
    }
}
