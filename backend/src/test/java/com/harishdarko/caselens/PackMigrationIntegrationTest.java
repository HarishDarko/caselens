package com.harishdarko.caselens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harishdarko.caselens.demo.DemoWorkspace;
import com.harishdarko.caselens.triage.PackCatalog;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V6 on a database that already has data from the old code, and the old code's writes after V6: the expand step
 * must keep both working, so V6 can run while the previous version still serves traffic.
 */
@Testcontainers
class PackMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("caselens")
            .withUsername("caselens")
            .withPassword("caselens");

    @Test
    void v6AddsTheIndustryWithoutBreakingRowsOrWritesFromTheOldCode() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("5").load().migrate();
        UUID before = UUID.randomUUID();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(oldCodeWorkspaceInsert(before));
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO evaluation_ground_truth (scenario_key, expected_category, expected_urgency) VALUES ('v5-saas', 'LOGIN', 'HIGH')"))
                    .isInstanceOf(SQLException.class);
        }

        Flyway latest = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load();
        latest.migrate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("6");

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(industry(statement, before)).isEqualTo("ev-charging");

            UUID after = UUID.randomUUID();
            statement.executeUpdate(oldCodeWorkspaceInsert(after));
            assertThat(industry(statement, after)).isEqualTo("ev-charging");
            try (ResultSet oldRead = statement.executeQuery("SELECT id, created_at, expires_at FROM demo_workspaces WHERE id = '" + after + "'")) {
                assertThat(oldRead.next()).isTrue();
            }

            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO demo_workspaces(id, created_at, expires_at, industry) VALUES ('"
                    + UUID.randomUUID() + "', now(), now() + interval '24 hours', NULL)")).isInstanceOf(SQLException.class);

            statement.executeUpdate(
                    "INSERT INTO evaluation_ground_truth (scenario_key, expected_category, expected_urgency) VALUES ('v6-saas', 'LOGIN', 'HIGH')");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO evaluation_ground_truth (scenario_key, expected_category, expected_urgency) VALUES ('v6-bad-urgency', 'LOGIN', 'SOON')"))
                    .isInstanceOf(SQLException.class);
            try (ResultSet checks = statement.executeQuery("SELECT count(*) FROM pg_constraint WHERE conname IN "
                    + "('chk_feedback_category', 'chk_ground_truth_category')")) {
                assertThat(checks.next()).isTrue();
                assertThat(checks.getInt(1)).isZero();
            }
            try (ResultSet truth = statement.executeQuery("SELECT count(*) FROM evaluation_ground_truth WHERE scenario_key <> 'v6-saas'")) {
                assertThat(truth.next()).isTrue();
                assertThat(truth.getInt(1)).isEqualTo(8);
            }
        }
    }

    @Test
    void theColumnDefaultTheEntityDefaultAndTheDefaultPackAgree() {
        assertThat(DemoWorkspace.DEFAULT_INDUSTRY).isEqualTo(PackCatalog.DEFAULT_PACK).isEqualTo("ev-charging");
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private String oldCodeWorkspaceInsert(UUID id) {
        return "INSERT INTO demo_workspaces(id, created_at, expires_at) VALUES ('" + id + "', now(), now() + interval '24 hours')";
    }

    private String industry(Statement statement, UUID id) throws SQLException {
        try (ResultSet row = statement.executeQuery("SELECT industry FROM demo_workspaces WHERE id = '" + id + "'")) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }
}
