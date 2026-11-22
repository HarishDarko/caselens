package com.harishdarko.caselens.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.harishdarko.caselens.demo.DemoRetentionSweep;
import com.harishdarko.caselens.demo.DemoWorkspace;
import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketChannel;
import com.harishdarko.caselens.triage.OutboxRelay;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** CL-04: one scheduled relay run on the real schema deletes expired rows and leaves live ones alone. */
@Testcontainers
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.jpa.hibernate.ddl-auto=validate"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RelayRetentionIntegrationTest {
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
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired OutboxRelay outboxRelay;
    @Autowired DemoRetentionSweep sweep;

    @Test
    void aRelayRunPurgesExpiredWorkspacesAndBucketsAndKeepsLiveOnes() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        UUID expired = UUID.randomUUID();
        UUID live = UUID.randomUUID();
        UUID expiredTicket = UUID.randomUUID();
        UUID liveTicket = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            entityManager.persist(new DemoWorkspace(expired, now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS)));
            entityManager.persist(new DemoWorkspace(live, now.minus(1, ChronoUnit.HOURS), now.plus(23, ChronoUnit.HOURS)));
            entityManager.persist(Ticket.create(expiredTicket, expired, "Expired workspace ticket",
                    "This ticket belongs to a workspace that expired yesterday.", TicketChannel.WEB, null, now));
            entityManager.persist(Ticket.create(liveTicket, live, "Live workspace ticket",
                    "This ticket belongs to a workspace that is still open today.", TicketChannel.WEB, null, now));
        });
        bucket("session:198.51.100.10", now.minus(10, ChronoUnit.MINUTES));
        bucket("ai-calls:2000-01-01", now.minus(1, ChronoUnit.DAYS));
        bucket("session:198.51.100.11", now.plus(1, ChronoUnit.MINUTES));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new RelayHandler(() -> mapper, () -> outboxRelay, () -> sweep)
                .handleRequest(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), output, null);

        JsonNode response = mapper.readTree(output.toByteArray());
        assertThat(response.path("status").asText()).isEqualTo("OK");
        assertThat(response.path("purgedWorkspaces").asInt()).isEqualTo(1);
        assertThat(response.path("purgedRateLimitBuckets").asInt()).isEqualTo(2);
        assertThat(count("select count(*) from demo_workspaces where id = ?", expired)).isZero();
        assertThat(count("select count(*) from tickets where id = ?", expiredTicket)).isZero();
        assertThat(count("select count(*) from demo_workspaces where id = ?", live)).isOne();
        assertThat(count("select count(*) from tickets where id = ?", liveTicket)).isOne();
        assertThat(jdbc.queryForList("select rate_key from rate_limit_buckets", String.class))
                .containsExactly("session:198.51.100.11");
    }

    private void bucket(String key, Instant expiresAt) {
        jdbc.update("insert into rate_limit_buckets(rate_key, window_started_at, expires_at, request_count) values (?, ?, ?, 1)",
                key, Timestamp.from(expiresAt.minus(1, ChronoUnit.MINUTES)), Timestamp.from(expiresAt));
    }

    private long count(String sql, UUID id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }
}
