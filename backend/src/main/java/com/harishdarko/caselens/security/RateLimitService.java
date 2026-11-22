package com.harishdarko.caselens.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
    /**
     * The in-memory map never holds more than this many windows. When it is full of live windows, the least recently
     * used per-request window makes room; budget counters are never dropped.
     */
    static final int MAX_IN_MEMORY_KEYS = 10_000;
    private final Clock clock;
    private final int defaultLimit;
    private final Duration window;
    /** In access order, so the first entry is the least recently used. Only touched under this object's lock. */
    private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>(16, 0.75f, true);
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
    public void acquire(String key, int limit, Duration windowLength) {
        acquire(key, limit, windowLength, false);
    }

    /**
     * Like {@link #acquire(String, int, Duration)} for a budget counter, such as the daily AI budget. In memory a
     * budget counter is never evicted to make room for other keys, since that would hand out a fresh budget.
     */
    public void acquireBudget(String key, int limit, Duration windowLength) {
        acquire(key, limit, windowLength, true);
    }

    private synchronized void acquire(String key, int limit, Duration windowLength, boolean budget) {
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
            current = new Window(now.plus(windowLength), 0, budget);
        }
        if (current.count() >= limit) throw exceeded(now, current.expiresAt());
        if (!windows.containsKey(boundedKey) && windows.size() >= MAX_IN_MEMORY_KEYS) makeRoom(now);
        windows.put(boundedKey, new Window(current.expiresAt(), current.count() + 1, current.budget()));
    }

    /**
     * Frees one slot for a new key: first every expired window, then the least recently used per-request window.
     * A budget counter (the daily AI budget and its per-client shares) is never dropped, because dropping it would
     * hand out a fresh day of budget. If every live window is a budget counter the new key is refused until the
     * soonest one expires. This limiter only runs outside Lambda,
     * where the client address is the socket address and cannot be forged per request.
     */
    private void makeRoom(Instant now) {
        windows.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
        if (windows.size() < MAX_IN_MEMORY_KEYS) return;
        Instant soonestExpiry = null;
        for (Iterator<Window> oldestFirst = windows.values().iterator(); oldestFirst.hasNext(); ) {
            Window candidate = oldestFirst.next();
            if (!candidate.budget()) {
                oldestFirst.remove();
                return;
            }
            if (soonestExpiry == null || candidate.expiresAt().isBefore(soonestExpiry)) soonestExpiry = candidate.expiresAt();
        }
        throw exceeded(now, soonestExpiry);
    }

    private static RateLimitExceededException exceeded(Instant now, Instant expiresAt) {
        long remainingMillis = Duration.between(now, expiresAt).toMillis();
        return new RateLimitExceededException(Math.max(1, (remainingMillis + 999) / 1000));
    }

    synchronized int inMemoryKeyCount() { return windows.size(); }

    private void acquireDistributed(String key, int limit, Duration windowLength) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(windowLength);
        DistributedWindow current = jdbcTemplate.queryForObject(DISTRIBUTED_ACQUIRE_SQL,
                (resultSet, rowNumber) -> new DistributedWindow(
                        resultSet.getInt("request_count"), resultSet.getTimestamp("expires_at").toInstant()),
                key, java.sql.Timestamp.from(now), java.sql.Timestamp.from(expiresAt));
        if (current == null || current.count() <= limit) return;
        throw exceeded(now, current.expiresAt());
    }

    /** {@code budget} marks a counter from {@link #acquireBudget}; see {@link #makeRoom}. */
    private record Window(Instant expiresAt, int count, boolean budget) {}
    private record DistributedWindow(int count, Instant expiresAt) {}
}
