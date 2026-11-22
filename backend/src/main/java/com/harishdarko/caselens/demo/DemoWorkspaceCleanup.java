package com.harishdarko.caselens.demo;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes expired demo workspaces. On Lambda the relay's 30-minute sweep calls it (see DemoRetentionSweep); elsewhere
 * DemoWorkspaceCleanupScheduler does. Each call is one short transaction over a bounded batch, and on PostgreSQL its
 * statements stop after {@link #STATEMENT_TIMEOUT}.
 */
@Component
public class DemoWorkspaceCleanup {
    static final int DEFAULT_BATCH = 100;
    static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(5);
    private static final Logger log = LoggerFactory.getLogger(DemoWorkspaceCleanup.class);
    private final DemoWorkspaceRepository workspaces;
    private final Clock clock;
    private final LocalStatementTimeout statementTimeout;

    @Autowired
    public DemoWorkspaceCleanup(DemoWorkspaceRepository workspaces, Clock clock, JdbcTemplate jdbc) {
        this.workspaces = workspaces;
        this.clock = clock;
        this.statementTimeout = new LocalStatementTimeout(jdbc, STATEMENT_TIMEOUT);
    }

    DemoWorkspaceCleanup(DemoWorkspaceRepository workspaces, Clock clock) {
        this(workspaces, clock, null);
    }

    @Transactional
    public int deleteExpired() {
        return deleteExpired(DEFAULT_BATCH);
    }

    /** Deletes up to {@code limit} workspaces that expired before now and returns how many went. */
    @Transactional
    public int deleteExpired(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Cleanup batch must be positive");
        statementTimeout.apply();
        int deleted = workspaces.deleteExpiredBatch(clock.instant(), limit);
        if (deleted > 0) log.info("demo.workspace_cleanup deleted={}", deleted);
        return deleted;
    }
}
