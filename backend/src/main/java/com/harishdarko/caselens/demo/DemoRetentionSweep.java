package com.harishdarko.caselens.demo;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Purges data that has outlived its use: expired demo workspaces (with everything that cascades from them) and expired
 * rate-limit buckets. Lambda freezes threads between invocations, so an in-JVM timer cannot do this there; the relay
 * function, which EventBridge already runs every 30 minutes, calls {@link #sweep(Duration)} instead.
 *
 * <p>Work happens in batches, each its own short transaction, until a batch comes back short or the time budget is
 * used. Whatever is left waits for the next run.
 */
@Component
public class DemoRetentionSweep {
    static final int WORKSPACE_BATCH = DemoWorkspaceCleanup.DEFAULT_BATCH;
    static final int BUCKET_BATCH = 1_000;
    private static final Logger log = LoggerFactory.getLogger(DemoRetentionSweep.class);
    private static final String DELETE_EXPIRED_BUCKETS = """
            DELETE FROM rate_limit_buckets WHERE rate_key IN (
                SELECT rate_key FROM rate_limit_buckets WHERE expires_at < ? ORDER BY expires_at LIMIT ?)
            """;
    private final DemoWorkspaceCleanup workspaces;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final LongSupplier nanoTime;

    @Autowired
    public DemoRetentionSweep(DemoWorkspaceCleanup workspaces, JdbcTemplate jdbc, Clock clock) {
        this(workspaces, jdbc, clock, System::nanoTime);
    }

    DemoRetentionSweep(DemoWorkspaceCleanup workspaces, JdbcTemplate jdbc, Clock clock, LongSupplier nanoTime) {
        this.workspaces = workspaces;
        this.jdbc = jdbc;
        this.clock = clock;
        this.nanoTime = nanoTime;
    }

    /** Purges workspaces, then rate-limit buckets, within one shared time budget. */
    public Result sweep(Duration budget) {
        long deadline = nanoTime.getAsLong() + budget.toNanos();
        int deletedWorkspaces = purgeExpiredWorkspaces(deadline);
        int deletedBuckets = purgeExpiredRateLimitBuckets(deadline);
        return new Result(deletedWorkspaces, deletedBuckets);
    }

    /** Only workspaces: outside Lambda the rate limiter keeps its windows in memory, not in the table. */
    public int purgeExpiredWorkspaces(Duration budget) {
        return purgeExpiredWorkspaces(nanoTime.getAsLong() + budget.toNanos());
    }

    private int purgeExpiredWorkspaces(long deadline) {
        return inBatches("workspaces", WORKSPACE_BATCH, deadline, () -> workspaces.deleteExpired(WORKSPACE_BATCH));
    }

    private int purgeExpiredRateLimitBuckets(long deadline) {
        return inBatches("rate_limit_buckets", BUCKET_BATCH, deadline,
                () -> jdbc.update(DELETE_EXPIRED_BUCKETS, Timestamp.from(clock.instant()), BUCKET_BATCH));
    }

    /** Runs at least one batch, then more while each batch comes back full and the deadline has not passed. */
    private int inBatches(String target, int batchSize, long deadline, IntSupplier batch) {
        int total = 0;
        try {
            int deleted;
            do {
                deleted = batch.getAsInt();
                total += deleted;
            } while (deleted >= batchSize && nanoTime.getAsLong() < deadline);
        } catch (DataAccessException failure) {
            // One failed target must not stop the other or fail the relay; the next run tries again.
            log.error("retention.sweep_failed target={} deletedBeforeFailure={} error={}", target, total,
                    failure.getClass().getSimpleName());
        }
        if (total > 0) log.info("retention.sweep target={} deleted={}", target, total);
        return total;
    }

    public record Result(int workspaces, int rateLimitBuckets) {}
}
