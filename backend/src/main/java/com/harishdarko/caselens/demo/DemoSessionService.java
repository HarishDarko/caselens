package com.harishdarko.caselens.demo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoSessionService {
    private static final Duration SESSION_TTL = Duration.ofHours(24);
    private final DemoWorkspaceRepository workspaces;
    private final DemoTokenService tokens;
    private final Clock clock;

    DemoSessionService(DemoWorkspaceRepository workspaces, DemoTokenService tokens, Clock clock) {
        this.workspaces = workspaces;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Transactional
    public DemoSession create() {
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = createdAt.plus(SESSION_TTL);
        UUID workspaceId = UUID.randomUUID();
        workspaces.save(new DemoWorkspace(workspaceId, createdAt, expiresAt));
        return new DemoSession(tokens.create(workspaceId, expiresAt), expiresAt);
    }

    public record DemoSession(String token, Instant expiresAt) {}
}
