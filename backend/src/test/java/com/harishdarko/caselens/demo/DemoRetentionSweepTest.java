package com.harishdarko.caselens.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

class DemoRetentionSweepTest {
    private final DemoWorkspaceCleanup workspaces = mock(DemoWorkspaceCleanup.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2040-07-11T12:00:00Z"), ZoneOffset.UTC);
    private final AtomicLong nanos = new AtomicLong();

    @Test
    void keepsDeletingFullBatchesUntilOneComesBackShort() {
        when(workspaces.deleteExpired(DemoRetentionSweep.WORKSPACE_BATCH))
                .thenReturn(DemoRetentionSweep.WORKSPACE_BATCH, DemoRetentionSweep.WORKSPACE_BATCH, 7);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(DemoRetentionSweep.BUCKET_BATCH, 3);

        DemoRetentionSweep.Result result = sweep().sweep(Duration.ofSeconds(10));

        assertThat(result).isEqualTo(new DemoRetentionSweep.Result(2 * DemoRetentionSweep.WORKSPACE_BATCH + 7,
                DemoRetentionSweep.BUCKET_BATCH + 3));
        verify(workspaces, times(3)).deleteExpired(DemoRetentionSweep.WORKSPACE_BATCH);
    }

    @Test
    void stopsStartingBatchesOnceTheTimeBudgetIsUsed() {
        // Every batch is full and takes 4 seconds, so a 10-second budget allows three workspace batches and then
        // one bucket batch (each target always gets its first batch).
        when(workspaces.deleteExpired(DemoRetentionSweep.WORKSPACE_BATCH)).thenAnswer(invocation -> {
            nanos.addAndGet(Duration.ofSeconds(4).toNanos());
            return DemoRetentionSweep.WORKSPACE_BATCH;
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(DemoRetentionSweep.BUCKET_BATCH);

        DemoRetentionSweep.Result result = sweep().sweep(Duration.ofSeconds(10));

        verify(workspaces, times(3)).deleteExpired(DemoRetentionSweep.WORKSPACE_BATCH);
        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
        assertThat(result.rateLimitBuckets()).isEqualTo(DemoRetentionSweep.BUCKET_BATCH);
    }

    @Test
    void aDatabaseFailureOnWorkspacesStillPurgesBucketsAndDoesNotThrow() {
        when(workspaces.deleteExpired(DemoRetentionSweep.WORKSPACE_BATCH)).thenThrow(new QueryTimeoutException("slow"));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(5);

        DemoRetentionSweep.Result result = sweep().sweep(Duration.ofSeconds(10));

        assertThat(result).isEqualTo(new DemoRetentionSweep.Result(0, 5));
    }

    @Test
    void theLocalTimerPurgesOnlyWorkspaces() {
        when(workspaces.deleteExpired(DemoRetentionSweep.WORKSPACE_BATCH)).thenReturn(2);

        assertThat(sweep().purgeExpiredWorkspaces(Duration.ofSeconds(30))).isEqualTo(2);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private DemoRetentionSweep sweep() {
        return new DemoRetentionSweep(workspaces, jdbc, clock, nanos::get);
    }
}
