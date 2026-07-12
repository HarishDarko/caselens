package com.harishdarko.caselens.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DemoWorkspaceCleanupTest {
    @Test
    void deletesExpiredWorkspacesUsingTheApplicationClock() {
        DemoWorkspaceRepository repository = mock(DemoWorkspaceRepository.class);
        Instant now = Instant.parse("2040-07-11T12:00:00Z");
        when(repository.deleteByExpiresAtBefore(now)).thenReturn(3L);

        int deleted = new DemoWorkspaceCleanup(repository, Clock.fixed(now, ZoneOffset.UTC)).deleteExpired();

        assertThat(deleted).isEqualTo(3);
        verify(repository).deleteByExpiresAtBefore(now);
    }
}
