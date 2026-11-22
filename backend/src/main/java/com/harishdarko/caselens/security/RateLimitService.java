package com.harishdarko.caselens.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;

public final class RateLimitService {
    private static final String DISTRIBUTED_ACQUIRE_SQL = """
            INSERT INTO rate_limit_buckets(rate_key, window_started_at, expires_at, request_count)
            VALUES (?, ?, ?, 1)
            ON CONFLICT (rate_key) DO UPDATE SET
                window_started_at = CASE WHEN rate_limit_buckets.expires_at <= EXCLUDED.window_started_at
                    THEN EXCLUDED.window_started_at ELSE rate_limit_buckets.window_started_at END,
                expires_at = CASE WHEN rate_limit_buckets.expires_at <= EXCLUDED.window_started_at
                    THEN EXCLUDED.expires_at ELSE rate_limit_buckets.expires_at END,
                request_count = CASE WHEN rate_limit_buckets.expires_at <= EXCLUDED.window_started_at
                    THEN 1 ELSE rate_limit_buckets.request_count + 1 END
            RETURNING request_count, expires_at
            """;
    /** The column is VARCHAR(255); longer keys are cut so a hostile value cannot fail the insert. */
    static final int MAX_KEY_LENGTH = 200;
    /** The in-memory map never holds more than this many windows, even when every window is still live. */
    static final int MAX_IN_MEMORY_KEYS = 10_000;
    private final Clock clock;
    private final int defaultLimit;
    private final Duration window;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbcTemplate;

    public RateLimitService(Clock clock, int defaultLimit, Duration window) {
        this(clock, defaultLimit, window, null);
    }

    public RateLimitService(Clock clock, int defaultLimit, Duration window, JdbcTemplate jdbcTemplate) {
        if (defaultLimit < 1) throw new IllegalArgumentException("Rate limit must be positive");
        if (window.isZero() || window.isNegative()) throw new IllegalArgumentException("Rate-limit window must be positive");
        this.clock = clock;
        this.defaultLimit = defaultLimit;
        this.window = window;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void acquire(String key) { acquire(key, defaultLimit); }

    public void acquire(String key, int limit) { acquire(key, limit, window); }

    /**
     * Counts one use of {@code key} in a window of the given length, which starts at the key's first use. With a
     * database this is one upsert on rate_limit_buckets, so every Lambda instance shares the count.
     */
    public synchronized void acquire(String key, int limit, Duration windowLength) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Rate-limit key is required");
        if (limit < 1) throw new IllegalArgumentException("Rate limit must be positive");
        if (windowLength.isZero() || windowLength.isNegative()) throw new IllegalArgumentException("Rate-limit window must be positive");
        String boundedKey = key.length() > MAX_KEY_LENGTH ? key.substring(0, MAX_KEY_LENGTH) : key;
        if (jdbcTemplate != null) {
            acquireDistributed(boundedKey, limit, windowLength);
            return;
        }
        Instant now = clock.instant();
        Window current = windows.get(boundedKey);
        if (current == null || !now.isBefore(current.expiresAt())) {
            current = new Window(now.plus(windowLength), 0);
        }
        if (current.count() >= limit) {
            long remainingMillis = Duration.between(now, current.expiresAt()).toMillis();
            long retryAfter = Math.max(1, (remainingMillis + 999) / 1000);
            throw new RateLimitExceededException(retryAfter);
        }
        if (!windows.containsKey(boundedKey) && windows.size() >= MAX_IN_MEMORY_KEYS) {
            windows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
            // Still full of live windows: start over rather than grow without bound. This limiter only runs
            // outside Lambda, where the client address is the socket address and cannot be forged per request.
            if (windows.size() >= MAX_IN_MEMORY_KEYS) windows.clear();
        }
        windows.put(boundedKey, new Window(current.expiresAt(), current.count() + 1));
    }

    int inMemoryKeyCount() { return windows.size(); }

    private void acquireDistributed(String key, int limit, Duration windowLength) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(windowLength);
        DistributedWindow current = jdbcTemplate.queryForObject(DISTRIBUTED_ACQUIRE_SQL,
                (resultSet, rowNumber) -> new DistributedWindow(
                        resultSet.getInt("request_count"), resultSet.getTimestamp("expires_at").toInstant()),
                key, java.sql.Timestamp.from(now), java.sql.Timestamp.from(expiresAt));
        if (current == null || current.count() <= limit) return;
        long remainingMillis = Duration.between(now, current.expiresAt()).toMillis();
        long retryAfter = Math.max(1, (remainingMillis + 999) / 1000);
        throw new RateLimitExceededException(retryAfter);
    }

    private record Window(Instant expiresAt, int count) {}
    private record DistributedWindow(int count, Instant expiresAt) {}
}
