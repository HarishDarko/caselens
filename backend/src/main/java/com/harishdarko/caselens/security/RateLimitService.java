package com.harishdarko.caselens.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RateLimitService {
    private final Clock clock;
    private final int defaultLimit;
    private final Duration window;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitService(Clock clock, int defaultLimit, Duration window) {
        if (defaultLimit < 1) throw new IllegalArgumentException("Rate limit must be positive");
        if (window.isZero() || window.isNegative()) throw new IllegalArgumentException("Rate-limit window must be positive");
        this.clock = clock;
        this.defaultLimit = defaultLimit;
        this.window = window;
    }

    public void acquire(String key) { acquire(key, defaultLimit); }

    public synchronized void acquire(String key, int limit) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Rate-limit key is required");
        if (limit < 1) throw new IllegalArgumentException("Rate limit must be positive");
        Instant now = clock.instant();
        Window current = windows.get(key);
        if (current == null || !now.isBefore(current.expiresAt())) {
            current = new Window(now.plus(window), 0);
        }
        if (current.count() >= limit) {
            long remainingMillis = Duration.between(now, current.expiresAt()).toMillis();
            long retryAfter = Math.max(1, (remainingMillis + 999) / 1000);
            throw new RateLimitExceededException(retryAfter);
        }
        windows.put(key, new Window(current.expiresAt(), current.count() + 1));
        if (windows.size() > 10_000) windows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }

    private record Window(Instant expiresAt, int count) {}
}
