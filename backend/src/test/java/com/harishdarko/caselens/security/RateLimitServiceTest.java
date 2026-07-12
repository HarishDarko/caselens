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
}
