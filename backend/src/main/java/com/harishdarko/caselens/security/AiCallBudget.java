package com.harishdarko.caselens.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * A global cap on AI provider calls per UTC day. The count lives in the rate-limit buckets the API already uses, so on
 * Lambda it is one upsert on rate_limit_buckets and every api and worker instance shares it. The bucket expires at the
 * next UTC midnight, and the relay's sweep deletes it after that.
 */
public final class AiCallBudget {
    static final String KEY_PREFIX = "ai-calls:";
    private final RateLimitService limits;
    private final Clock clock;
    private final int dailyLimit;

    public AiCallBudget(RateLimitService limits, Clock clock, int dailyLimit) {
        if (dailyLimit < 0) throw new IllegalArgumentException("Daily AI call budget must not be negative");
        this.limits = limits;
        this.clock = clock;
        this.dailyLimit = dailyLimit;
    }

    /** True when one more AI call fits in today's budget; the call is counted. A budget of 0 turns AI calls off. */
    public boolean tryAcquire() {
        if (dailyLimit == 0) return false;
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Instant nextMidnight = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        try {
            limits.acquire(KEY_PREFIX + today, dailyLimit, Duration.between(now, nextMidnight));
            return true;
        } catch (RateLimitExceededException spent) {
            return false;
        }
    }

    public int dailyLimit() { return dailyLimit; }
}
