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
import org.flywaydb.core.Flyway;
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

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);

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
        }
    }

    private String validTicketInsert(UUID workspace, UUID ticket, String displayId) {
        return "INSERT INTO tickets(id, workspace_id, display_id, subject, message, channel, status, created_at, updated_at) "
                + "VALUES ('" + ticket + "', '" + workspace + "', '" + displayId
                + "', 'Synthetic issue', 'A synthetic ticket message that passes database validation.', "
                + "'WEB', 'NEW', now(), now())";
    }
}
