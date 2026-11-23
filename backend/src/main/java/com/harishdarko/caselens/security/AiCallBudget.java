package com.harishdarko.caselens.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * A global cap on AI provider calls per UTC day, and a smaller daily share of it for each client. The counts live in
 * the rate-limit buckets the API already uses, so on Lambda each is one upsert on rate_limit_buckets and every api and
 * worker instance shares them. The buckets expire at the next UTC midnight, and the relay's sweep deletes them after
 * that.
 *
 * <p>The share stops one client from spending the whole day's budget for everyone: past it, that client's triage uses
 * the rules provider while other visitors still get AI answers. A client is the network that opened the workspace
 * (see {@link ClientAddresses#clientKey}).
 */
public final class AiCallBudget {
    static final String KEY_PREFIX = "ai-calls:";
    static final String CLIENT_SHARE_KEY_PREFIX = "ai-share:";
    static final String EVENT_KEY_PREFIX = "ai-event:";
    /** Longer than every delivery of one job: SQS gives up on a message within about 10 minutes. */
    static final Duration EVENT_WINDOW = Duration.ofMinutes(30);
    private final RateLimitService limits;
    private final Clock clock;
    private final int dailyLimit;
    private final int dailySharePerClient;

    /** The outcome of asking for one AI call. */
    public enum Grant { GRANTED, CLIENT_SHARE_SPENT, BUDGET_SPENT }

    /** A global budget with no per-client share. */
    public AiCallBudget(RateLimitService limits, Clock clock, int dailyLimit) {
        this(limits, clock, dailyLimit, 0);
    }

    /** A share of 0 turns the per-client share off; the global budget still applies. */
    public AiCallBudget(RateLimitService limits, Clock clock, int dailyLimit, int dailySharePerClient) {
        if (dailyLimit < 0) throw new IllegalArgumentException("Daily AI call budget must not be negative");
        if (dailySharePerClient < 0) throw new IllegalArgumentException("Daily AI call share must not be negative");
        this.limits = limits;
        this.clock = clock;
        this.dailyLimit = dailyLimit;
        this.dailySharePerClient = dailySharePerClient;
    }

    /** True when one more AI call fits in today's budget; the call is counted. A budget of 0 turns AI calls off. */
    public boolean tryAcquire() {
        return tryAcquire(null) == Grant.GRANTED;
    }

    /**
     * Counts one AI call for the client, then for the whole budget. The share is checked first, so a client past its
     * share never spends the global budget. A null client key (a workspace opened before shares existed) counts
     * against the global budget only.
     */
    public Grant tryAcquire(String clientKey) {
        if (dailyLimit == 0) return Grant.BUDGET_SPENT;
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Duration untilMidnight = Duration.between(now, today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        if (dailySharePerClient > 0 && clientKey != null && !clientKey.isBlank()) {
            try {
                limits.acquireBudget(CLIENT_SHARE_KEY_PREFIX + today + ":" + clientKey, dailySharePerClient, untilMidnight);
            } catch (RateLimitExceededException spent) {
                return Grant.CLIENT_SHARE_SPENT;
            }
        }
        try {
            limits.acquireBudget(KEY_PREFIX + today, dailyLimit, untilMidnight);
            return Grant.GRANTED;
        } catch (RateLimitExceededException spent) {
            return Grant.BUDGET_SPENT;
        }
    }

    /**
     * Like {@link #tryAcquire(String)}, but once per queued job: the first delivery of an event counts the call, and
     * every later delivery of the same event (an SQS redelivery after a rate limit or a crash) is granted without
     * counting again, so a redelivery never costs a visitor a second unit of their share. The marker is one more
     * rate-limit bucket with a limit of 1, so it is shared by every worker instance. A null event counts every call.
     */
    public Grant tryAcquire(String clientKey, java.util.UUID eventId) {
        if (eventId != null) {
            if (dailyLimit == 0) return Grant.BUDGET_SPENT;
            try {
                limits.acquire(EVENT_KEY_PREFIX + eventId, 1, EVENT_WINDOW);
            } catch (RateLimitExceededException alreadyCounted) {
                return Grant.GRANTED;
            }
        }
        return tryAcquire(clientKey);
    }

    public int dailyLimit() { return dailyLimit; }

    public int dailySharePerClient() { return dailySharePerClient; }
}
