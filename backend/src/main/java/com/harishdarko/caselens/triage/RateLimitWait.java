package com.harishdarko.caselens.triage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpHeaders;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one rule both AI providers follow when they answer 429: how long the provider asks the caller to wait, and
 * what to do about it inside one call.
 *
 * <ul>
 * <li>{@link Decision.Kind#RETRY_NOW}: no wait was given; retry after a short jitter.</li>
 * <li>{@link Decision.Kind#WAIT}: the wait, a sleep-overshoot margin, the connect allowance and a useful retry all fit
 * in what is left of the call budget; sleep, then retry once.</li>
 * <li>{@link Decision.Kind#RETRY_LATER}: it does not fit, but the limit clears before the redelivery's own call could
 * get past it; end the call as HTTP_429 so the worker can leave the job for one SQS redelivery.</li>
 * <li>{@link Decision.Kind#GIVE_UP}: the limit would still stand when the redelivery's call could no longer wait for
 * it (a daily limit, say); end the call as RATE_LIMIT_LONG_WAIT, which falls back to the rules at once.</li>
 * </ul>
 */
final class RateLimitWait {
    /** The triage queue's visibility timeout: SQS hands an unacknowledged message out again this long after receipt. */
    static final Duration REDELIVERY_DELAY = Duration.ofSeconds(90);
    /** A parsed wait is clamped to this, so no header value can overflow. */
    static final Duration MAX_WAIT = Duration.ofDays(1);
    /** Thread.sleep wakes late; an approved wait keeps this much extra, so the retry still has its minimum time. */
    static final Duration SLEEP_OVERSHOOT = Duration.ofSeconds(1);
    /**
     * Measured from when a delivery was received, the latest moment a rate limit can clear and still be cured by the
     * redelivery: it starts REDELIVERY_DELAY after the receipt and can then wait in its own call for all but the
     * overshoot margin, the connect allowance and the minimum retry. A limit that clears later gives up at once.
     */
    static final Duration RETRY_HORIZON = REDELIVERY_DELAY.plus(GroqTriageProvider.CALL_BUDGET)
            .minus(SLEEP_OVERSHOOT).minus(GroqTriageProvider.CONNECT_ALLOWANCE).minus(GroqTriageProvider.MIN_RETRY_TIMEOUT);
    static final String LONG_WAIT_CODE = "RATE_LIMIT_LONG_WAIT";
    private static final String NUMBER = "\\d+(?:\\.\\d+)?";
    private static final Pattern SECONDS = Pattern.compile(NUMBER);
    /** A Go duration as the x-ratelimit-reset-* headers send it: "7.66s", "2m59.56s", "1h2m3s", "250ms", "500µs". */
    private static final Pattern GO_DURATION = Pattern.compile("(?:(" + NUMBER + ")h)?(?:(" + NUMBER + ")m(?!s))?"
            + "(?:(" + NUMBER + ")s)?(?:(" + NUMBER + ")ms)?(?:(" + NUMBER + ")(?:µs|μs|us))?(?:(" + NUMBER + ")ns)?");
    private static final long[] UNIT_NANOS = {3_600_000_000_000L, 60_000_000_000L, 1_000_000_000L, 1_000_000L, 1_000L, 1L};
    private static final BigDecimal MAX_NANOS = BigDecimal.valueOf(MAX_WAIT.toNanos());

    /** Waits between requests; a test passes one that records the wait instead of sleeping. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    record Decision(Kind kind, Duration delay) {
        enum Kind { RETRY_NOW, WAIT, RETRY_LATER, GIVE_UP }
    }

    private RateLimitWait() {}

    /**
     * What to do about a 429, given how long the call has run and its budget. {@code minRetry} and
     * {@code connectAllowance} are the provider's own: a retry gets at least {@code minRetry} after the connect
     * allowance. {@code sinceReceived} is the time since the queue handed over this delivery: the limit clears at
     * {@code sinceReceived + wait} on that clock, which is judged against RETRY_HORIZON, not a fixed wait.
     */
    static Decision decide(HttpHeaders headers, Clock clock, Duration elapsed, Duration budget, Duration connectAllowance,
            Duration minRetry, Duration sinceReceived) {
        Optional<Duration> asked = choose(headers, clock);
        if (asked.isEmpty()) return new Decision(Decision.Kind.RETRY_NOW, Duration.ZERO);
        Duration wait = asked.get();
        Duration needed = elapsed.plus(wait).plus(SLEEP_OVERSHOOT).plus(connectAllowance).plus(minRetry);
        if (needed.compareTo(budget) <= 0) return new Decision(Decision.Kind.WAIT, wait);
        return sinceReceived.plus(wait).compareTo(RETRY_HORIZON) > 0 ? new Decision(Decision.Kind.GIVE_UP, wait)
                : new Decision(Decision.Kind.RETRY_LATER, wait);
    }

    /**
     * The wait a 429 asks for. retry-after first (seconds or an HTTP date). Without it, the reset of the limit that is
     * exhausted (its remaining count is 0); with no remaining counts, the token reset, then the request reset.
     */
    static Optional<Duration> choose(HttpHeaders headers, Clock clock) {
        Optional<String> retryAfter = headers.firstValue("retry-after");
        if (retryAfter.isPresent()) {
            Optional<Duration> wait = parseDuration(retryAfter.get()).or(() -> parseHttpDate(retryAfter.get(), clock));
            if (wait.isPresent()) return wait;
        }
        boolean requestsSpent = headers.firstValue("x-ratelimit-remaining-requests").map(value -> value.strip().equals("0")).orElse(false);
        boolean tokensSpent = headers.firstValue("x-ratelimit-remaining-tokens").map(value -> value.strip().equals("0")).orElse(false);
        Optional<Duration> requests = headers.firstValue("x-ratelimit-reset-requests").flatMap(RateLimitWait::parseDuration);
        Optional<Duration> tokens = headers.firstValue("x-ratelimit-reset-tokens").flatMap(RateLimitWait::parseDuration);
        if (requestsSpent && tokensSpent) {
            return requests.isPresent() && tokens.isPresent()
                    ? Optional.of(requests.get().compareTo(tokens.get()) >= 0 ? requests.get() : tokens.get())
                    : requests.or(() -> tokens);
        }
        if (requestsSpent && requests.isPresent()) return requests;
        if (tokensSpent && tokens.isPresent()) return tokens;
        return tokens.or(() -> requests);
    }

    /** Seconds ("3", "1.5") or a Go duration, rounded up to whole milliseconds and clamped to MAX_WAIT. */
    static Optional<Duration> parseDuration(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String text = value.strip();
        if (SECONDS.matcher(text).matches()) return Optional.of(clamped(new BigDecimal(text).multiply(BigDecimal.valueOf(UNIT_NANOS[2]))));
        Matcher matcher = GO_DURATION.matcher(text);
        if (!matcher.matches()) return Optional.empty();
        BigDecimal nanos = BigDecimal.ZERO;
        boolean any = false;
        for (int group = 1; group <= UNIT_NANOS.length; group++) {
            if (matcher.group(group) == null) continue;
            any = true;
            nanos = nanos.add(new BigDecimal(matcher.group(group)).multiply(BigDecimal.valueOf(UNIT_NANOS[group - 1])));
        }
        return any ? Optional.of(clamped(nanos)) : Optional.empty();
    }

    /** An RFC 9110 HTTP date ("Mon, 23 Nov 2026 12:00:42 GMT"), as a wait from now; a past date is no wait. */
    static Optional<Duration> parseHttpDate(String value, Clock clock) {
        try {
            ZonedDateTime when = ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration wait = Duration.between(clock.instant(), when.toInstant());
            if (wait.isNegative()) return Optional.of(Duration.ZERO);
            if (wait.compareTo(MAX_WAIT) > 0) return Optional.of(MAX_WAIT);
            // Whole milliseconds, rounded up: a positive wait shorter than 1 ms must not become sleep(0).
            return Optional.of(Duration.ofMillis(wait.toMillis() + (wait.toNanosPart() % 1_000_000 == 0 ? 0 : 1)));
        } catch (DateTimeParseException notADate) {
            return Optional.empty();
        }
    }

    private static Duration clamped(BigDecimal nanos) {
        if (nanos.compareTo(MAX_NANOS) >= 0) return MAX_WAIT;
        long millis = nanos.divide(BigDecimal.valueOf(UNIT_NANOS[3]), 0, RoundingMode.CEILING).longValueExact();
        return Duration.ofMillis(millis);
    }
}
