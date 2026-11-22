package com.harishdarko.caselens;

import static org.assertj.core.api.Assertions.assertThat;

import com.harishdarko.caselens.common.WorkspaceLimitExceededException;
import com.harishdarko.caselens.demo.DemoRetentionSweep;
import com.harishdarko.caselens.demo.DemoSessionService;
import com.harishdarko.caselens.ticket.TicketChannel;
import com.harishdarko.caselens.ticket.TicketService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** CL2-08 and CL2-12: races that only a real PostgreSQL shows, with its row locks and READ COMMITTED rechecks. */
@Testcontainers
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "CASELENS_LIMIT_TICKETS_PER_WORKSPACE=3",
    "spring.jpa.hibernate.ddl-auto=validate"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresRaceIntegrationTest {
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

    @Autowired DemoSessionService sessions;
    @Autowired TicketService tickets;
    @Autowired DemoRetentionSweep sweep;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void concurrentTicketCreatesNeverPassTheWorkspaceCap() throws Exception {
        UUID workspaceId = sessions.create("ev-charging", "0123456789abcdef0123456789abcdef").workspaceId();
        int callers = 12;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                outcomes.add(pool.submit(() -> {
                    start.await();
                    try {
                        tickets.create(workspaceId, "Charger offline", "The charger at bay four is offline again today.",
                                TicketChannel.WEB);
                        return true;
                    } catch (WorkspaceLimitExceededException full) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int created = 0;
            for (Future<Boolean> outcome : outcomes) if (outcome.get(30, TimeUnit.SECONDS)) created++;

            assertThat(created).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from tickets where workspace_id = ?", Long.class, workspaceId))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject("select client_key from demo_workspaces where id = ?", String.class, workspaceId))
                .isEqualTo("0123456789abcdef0123456789abcdef");
    }

    /**
     * The purge lists expired buckets from its snapshot. A request that refreshes one of them into a new window
     * while the purge runs holds the row; once it commits, the purge must recheck the new row and keep it.
     */
    @Test
    void thePurgeKeepsABucketThatWasRefreshedWhileItRan() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("insert into rate_limit_buckets(rate_key, window_started_at, expires_at, request_count) values (?, ?, ?, 5)",
                "session:race", Timestamp.from(now.minus(2, ChronoUnit.MINUTES)), Timestamp.from(now.minus(1, ChronoUnit.MINUTES)));

        try (Connection request = dataSource.getConnection()) {
            request.setAutoCommit(false);
            try (PreparedStatement refresh = request.prepareStatement(
                    "update rate_limit_buckets set window_started_at = ?, expires_at = ?, request_count = 1 where rate_key = ?")) {
                refresh.setTimestamp(1, Timestamp.from(now));
                refresh.setTimestamp(2, Timestamp.from(now.plus(1, ChronoUnit.MINUTES)));
                refresh.setString(3, "session:race");
                assertThat(refresh.executeUpdate()).isOne();
            }

            CompletableFuture<DemoRetentionSweep.Result> purge =
                    CompletableFuture.supplyAsync(() -> sweep.sweep(Duration.ofSeconds(30)));
            awaitPurgeBlockedOnTheRow();
            request.commit();

            assertThat(purge.get(30, TimeUnit.SECONDS).rateLimitBuckets()).isZero();
        }
        assertThat(jdbc.queryForObject("select request_count from rate_limit_buckets where rate_key = ?", Integer.class,
                "session:race")).isOne();
    }

    private void awaitPurgeBlockedOnTheRow() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                    where wait_event_type = 'Lock' and query like '%DELETE FROM rate_limit_buckets%'
                    """, Long.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("The purge never waited on the refreshed bucket");
    }
}
