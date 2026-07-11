package com.harishdarko.caselens.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoSessionService {
    private static final Duration SESSION_TTL = Duration.ofHours(24);
    private final byte[] expectedPasscode;
    private final DemoWorkspaceRepository workspaces;
    private final DemoTokenService tokens;
    private final Clock clock;

    DemoSessionService(@Value("${caselens.demo-passcode}") String expectedPasscode,
            DemoWorkspaceRepository workspaces, DemoTokenService tokens, Clock clock) {
        this.expectedPasscode = expectedPasscode.getBytes(StandardCharsets.UTF_8);
        this.workspaces = workspaces;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Transactional
    public DemoSession create(String passcode) {
        byte[] supplied = passcode == null ? new byte[0] : passcode.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedPasscode, supplied)) throw new InvalidPasscodeException();

        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = createdAt.plus(SESSION_TTL);
        UUID workspaceId = UUID.randomUUID();
        workspaces.save(new DemoWorkspace(workspaceId, createdAt, expiresAt));
        return new DemoSession(tokens.create(workspaceId, expiresAt), expiresAt);
    }

    public record DemoSession(String token, Instant expiresAt) {}
}
