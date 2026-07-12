package com.harishdarko.caselens.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
}
