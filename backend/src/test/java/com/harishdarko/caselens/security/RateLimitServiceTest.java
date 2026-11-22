package com.harishdarko.caselens.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.junit.jupiter.api.Test;

class RateLimitServiceTest {
    @Test
    void rejectsRequestsAfterTheConfiguredWindowBudget() {
        RateLimitService service = new RateLimitService(
                Clock.fixed(Instant.parse("2040-07-11T12:00:00Z"), ZoneOffset.UTC), 2, Duration.ofMinutes(1));

        service.acquire("workspace-a");
        service.acquire("workspace-a");

        assertThatThrownBy(() -> service.acquire("workspace-a"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("Rate limit exceeded")
                .extracting(exception -> ((RateLimitExceededException) exception).retryAfterSeconds())
                .isEqualTo(60L);
    }

    @Test
    void usesAnAtomicPostgresBucketForTheLambdaProfile() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getInt("request_count")).thenReturn(3);
        when(resultSet.getTimestamp("expires_at"))
                .thenReturn(Timestamp.from(Instant.parse("2040-07-11T12:01:00Z")));
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> ((RowMapper<?>) invocation.getArgument(1)).mapRow(resultSet, 0));
        RateLimitService service = new RateLimitService(
                Clock.fixed(Instant.parse("2040-07-11T12:00:00Z"), ZoneOffset.UTC), 2, Duration.ofMinutes(1), jdbc);

        assertThatThrownBy(() -> service.acquire("198.51.100.10"))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting(exception -> ((RateLimitExceededException) exception).retryAfterSeconds())
                .isEqualTo(60L);
        verify(jdbc).queryForObject(org.mockito.ArgumentMatchers.contains("ON CONFLICT (rate_key)"), any(RowMapper.class),
                any(Object[].class));
    }

    @Test
    void cutsLongKeysSoTheyFitTheBucketColumn() {
        RateLimitService service = new RateLimitService(
                Clock.fixed(Instant.parse("2040-07-11T12:00:00Z"), ZoneOffset.UTC), 1, Duration.ofMinutes(1));
        String hostile = "session:" + "9".repeat(5_000);

        service.acquire(hostile);

        // The cut key is the same bucket, so a second request with the same long value is still limited.
        assertThatThrownBy(() -> service.acquire(hostile)).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void neverHoldsMoreInMemoryWindowsThanTheBound() {
        RateLimitService service = new RateLimitService(
                Clock.fixed(Instant.parse("2040-07-11T12:00:00Z"), ZoneOffset.UTC), 5, Duration.ofMinutes(1));

        for (int key = 0; key < RateLimitService.MAX_IN_MEMORY_KEYS + 500; key++) service.acquire("session:" + key);

        org.assertj.core.api.Assertions.assertThat(service.inMemoryKeyCount())
                .isLessThanOrEqualTo(RateLimitService.MAX_IN_MEMORY_KEYS);
    }

    /** CL2-06: a full map used to be cleared, which wiped the live daily AI budget along with everything else. */
    @Test
    void aFullMapNeverDropsTheDailyBudgetCounters() {
        RateLimitService service = new RateLimitService(
                Clock.fixed(Instant.parse("2040-07-11T12:00:00Z"), ZoneOffset.UTC), 5, Duration.ofMinutes(1));
        service.acquireBudget("ai-calls:2040-07-11", 1, Duration.ofHours(12));
        service.acquireBudget("ai-share:2040-07-11:client-a", 1, Duration.ofHours(12));

        for (int key = 0; key < RateLimitService.MAX_IN_MEMORY_KEYS + 500; key++) service.acquire("session:" + key);

        org.assertj.core.api.Assertions.assertThat(service.inMemoryKeyCount())
                .isEqualTo(RateLimitService.MAX_IN_MEMORY_KEYS);
        assertThatThrownBy(() -> service.acquireBudget("ai-calls:2040-07-11", 1, Duration.ofHours(12)))
                .isInstanceOf(RateLimitExceededException.class);
        assertThatThrownBy(() -> service.acquireBudget("ai-share:2040-07-11:client-a", 1, Duration.ofHours(12)))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void aFullMapEvictsTheLeastRecentlyUsedRequestWindowFirst() {
        RateLimitService service = new RateLimitService(
                Clock.fixed(Instant.parse("2040-07-11T12:00:00Z"), ZoneOffset.UTC), 2, Duration.ofMinutes(1));
        for (int key = 0; key < RateLimitService.MAX_IN_MEMORY_KEYS; key++) service.acquire("session:" + key);
        service.acquire("session:0");

        service.acquire("session:new");

        // session:0 was used last, so session:1 made room; session:0 keeps its count of 2 and is still limited.
        assertThatThrownBy(() -> service.acquire("session:0")).isInstanceOf(RateLimitExceededException.class);
        service.acquire("session:1");
        service.acquire("session:1");
        assertThatThrownBy(() -> service.acquire("session:1")).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void expiredWindowsMakeRoomBeforeAnyLiveOne() {
        MutableClock clock = new MutableClock(Instant.parse("2040-07-11T12:00:00Z"));
        RateLimitService service = new RateLimitService(clock, 1, Duration.ofMinutes(1));
        service.acquire("session:live", 1, Duration.ofMinutes(10));
        for (int key = 1; key < RateLimitService.MAX_IN_MEMORY_KEYS; key++) service.acquire("session:" + key);
        clock.advance(Duration.ofMinutes(2));

        service.acquire("session:new");

        org.assertj.core.api.Assertions.assertThat(service.inMemoryKeyCount()).isEqualTo(2);
        assertThatThrownBy(() -> service.acquire("session:live")).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void aMapFullOfLiveBudgetCountersRefusesNewKeysInsteadOfDroppingOne() {
        RateLimitService service = new RateLimitService(
                Clock.fixed(Instant.parse("2040-07-11T12:00:00Z"), ZoneOffset.UTC), 5, Duration.ofMinutes(1));
        for (int key = 0; key < RateLimitService.MAX_IN_MEMORY_KEYS; key++) {
            service.acquireBudget("ai-share:2040-07-11:" + key, 1, Duration.ofHours(12));
        }

        assertThatThrownBy(() -> service.acquire("session:new"))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting(exception -> ((RateLimitExceededException) exception).retryAfterSeconds())
                .isEqualTo(12 * 3600L);
        org.assertj.core.api.Assertions.assertThat(service.inMemoryKeyCount())
                .isEqualTo(RateLimitService.MAX_IN_MEMORY_KEYS);
        assertThatThrownBy(() -> service.acquireBudget("ai-share:2040-07-11:0", 1, Duration.ofHours(12)))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void acceptsAPerCallWindowForDailyBudgets() {
        RateLimitService service = new RateLimitService(
                Clock.fixed(Instant.parse("2040-07-11T12:00:00Z"), ZoneOffset.UTC), 5, Duration.ofMinutes(1));

        service.acquire("ai-calls:2040-07-11", 1, Duration.ofHours(12));

        assertThatThrownBy(() -> service.acquire("ai-calls:2040-07-11", 1, Duration.ofHours(12)))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting(exception -> ((RateLimitExceededException) exception).retryAfterSeconds())
                .isEqualTo(12 * 3600L);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
