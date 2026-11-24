package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RateLimitWaitTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void parsesSecondsAndGoDurationsDownToNanoseconds() {
        assertThat(RateLimitWait.parseDuration("3")).contains(Duration.ofSeconds(3));
        assertThat(RateLimitWait.parseDuration("1.5")).contains(Duration.ofMillis(1_500));
        assertThat(RateLimitWait.parseDuration("7.66s")).contains(Duration.ofMillis(7_660));
        assertThat(RateLimitWait.parseDuration("2m59.56s")).contains(Duration.ofMillis(179_560));
        assertThat(RateLimitWait.parseDuration("1h2m3s")).contains(Duration.ofSeconds(3_723));
        assertThat(RateLimitWait.parseDuration("250ms")).contains(Duration.ofMillis(250));
        assertThat(RateLimitWait.parseDuration("500µs")).as("micro sign").contains(Duration.ofMillis(1));
        assertThat(RateLimitWait.parseDuration("500μs")).as("Greek mu").contains(Duration.ofMillis(1));
        assertThat(RateLimitWait.parseDuration("1500us")).contains(Duration.ofMillis(2));
        assertThat(RateLimitWait.parseDuration("250ns")).contains(Duration.ofMillis(1));
        assertThat(RateLimitWait.parseDuration("0s")).contains(Duration.ZERO);
        assertThat(RateLimitWait.parseDuration("soon")).isEmpty();
        assertThat(RateLimitWait.parseDuration("")).isEmpty();
        assertThat(RateLimitWait.parseDuration(null)).isEmpty();
    }

    @Test
    void clampsHugeValuesInsteadOfOverflowing() {
        assertThat(RateLimitWait.parseDuration("9999999999999999")).contains(RateLimitWait.MAX_WAIT);
        assertThat(RateLimitWait.parseDuration("9999999999999h")).contains(RateLimitWait.MAX_WAIT);
        assertThat(RateLimitWait.parseDuration("99999999999999999999999999999999ms")).contains(RateLimitWait.MAX_WAIT);
    }

    @Test
    void readsAnHttpDateRetryAfterAgainstTheClock() {
        assertThat(RateLimitWait.choose(headers(Map.of("retry-after", "Mon, 23 Nov 2026 12:00:42 GMT")), CLOCK))
                .contains(Duration.ofSeconds(42));
        assertThat(RateLimitWait.choose(headers(Map.of("retry-after", "Mon, 23 Nov 2026 11:59:00 GMT")), CLOCK))
                .as("a date in the past means retry now").contains(Duration.ZERO);
    }

    @Test
    void roundsSubMillisecondPositiveHttpDateWaitUpToOneMillisecond() {
        Clock halfMillisBefore = Clock.fixed(Instant.parse("2026-11-23T12:00:00.999500Z"), ZoneOffset.UTC);

        assertThat(RateLimitWait.choose(headers(Map.of("retry-after", "Mon, 23 Nov 2026 12:00:01 GMT")), halfMillisBefore))
                .as("sub-millisecond positive wait must not truncate to sleep(0)")
                .contains(Duration.ofMillis(1));
    }

    @Test
    void givesUpOnlyWhenTheLimitClearsAfterTheLatestMomentTheRedeliveryCouldWaitFor() {
        java.net.http.HttpHeaders eightyFive = headers(Map.of("retry-after", "85"));
        Duration budget = GroqTriageProvider.CALL_BUDGET;
        Duration connect = GroqTriageProvider.CONNECT_ALLOWANCE;
        Duration minRetry = GroqTriageProvider.MIN_RETRY_TIMEOUT;

        // 127 s is the horizon, measured from receipt: 85 s asked after 42 s clears exactly at it, after 43 s it does not.
        assertThat(RateLimitWait.decide(eightyFive, CLOCK, Duration.ofSeconds(1), budget, connect, minRetry, Duration.ofSeconds(1)).kind())
                .isEqualTo(RateLimitWait.Decision.Kind.RETRY_LATER);
        assertThat(RateLimitWait.decide(eightyFive, CLOCK, Duration.ofSeconds(1), budget, connect, minRetry, Duration.ofSeconds(42)).kind())
                .isEqualTo(RateLimitWait.Decision.Kind.RETRY_LATER);
        assertThat(RateLimitWait.decide(eightyFive, CLOCK, Duration.ofSeconds(1), budget, connect, minRetry, Duration.ofSeconds(43)).kind())
                .isEqualTo(RateLimitWait.Decision.Kind.GIVE_UP);
        // A wait that fits the call budget is always waited out, whatever the clock since receipt says.
        assertThat(RateLimitWait.decide(headers(Map.of("retry-after", "8")), CLOCK, Duration.ZERO, budget, connect, minRetry,
                Duration.ofSeconds(200)).kind()).isEqualTo(RateLimitWait.Decision.Kind.WAIT);
    }

    @Test
    void retryAfterWinsOverTheResetHeaders() {
        assertThat(RateLimitWait.choose(headers(Map.of("retry-after", "3", "x-ratelimit-reset-tokens", "9.5s")), CLOCK))
                .contains(Duration.ofSeconds(3));
    }

    @Test
    void withoutRetryAfterTheResetOfTheExhaustedLimitIsUsed() {
        assertThat(RateLimitWait.choose(headers(Map.of(
                "x-ratelimit-remaining-requests", "0", "x-ratelimit-reset-requests", "2m59s",
                "x-ratelimit-remaining-tokens", "5000", "x-ratelimit-reset-tokens", "0s")), CLOCK))
                .as("the request limit tripped, not the token limit").contains(Duration.ofSeconds(179));
        assertThat(RateLimitWait.choose(headers(Map.of(
                "x-ratelimit-remaining-requests", "120", "x-ratelimit-reset-requests", "2m59s",
                "x-ratelimit-remaining-tokens", "0", "x-ratelimit-reset-tokens", "7.66s")), CLOCK))
                .contains(Duration.ofMillis(7_660));
        assertThat(RateLimitWait.choose(headers(Map.of(
                "x-ratelimit-reset-requests", "2m59s", "x-ratelimit-reset-tokens", "7.66s")), CLOCK))
                .as("no remaining counts: the per-minute token reset").contains(Duration.ofMillis(7_660));
        assertThat(RateLimitWait.choose(headers(Map.of()), CLOCK)).isEmpty();
    }

    private static HttpHeaders headers(Map<String, String> values) {
        java.util.Map<String, List<String>> map = new java.util.HashMap<>();
        values.forEach((name, value) -> map.put(name, List.of(value)));
        return HttpHeaders.of(map, (name, value) -> true);
    }
}
