package com.harishdarko.caselens.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DemoTokenServiceTest {
    private static final String SECRET = "test-secret-that-is-at-least-thirty-two-bytes";
    private static final Instant NOW = Instant.parse("2040-07-11T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final DemoTokenService service = new DemoTokenService(SECRET, clock);

    @Test
    void createsAndVerifiesAWorkspaceScopedToken() {
        UUID workspaceId = UUID.randomUUID();
        Instant expiresAt = clock.instant().plusSeconds(3600);

        String token = service.create(workspaceId, expiresAt);
        DemoPrincipal principal = service.verify(token);

        assertThat(principal.workspaceId()).isEqualTo(workspaceId);
        assertThat(principal.expiresAt()).isEqualTo(expiresAt.truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void rejectsATamperedTokenWithoutExposingVerificationDetails() {
        String token = service.create(UUID.randomUUID(), clock.instant().plusSeconds(3600));
        int signatureStart = token.lastIndexOf('.') + 1;
        char firstSignatureCharacter = token.charAt(signatureStart);
        char replacement = firstSignatureCharacter == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(InvalidDemoTokenException.class)
                .hasMessage("Invalid or expired demo session");
    }

    @Test
    void rejectsAnExpiredToken() {
        String token = service.create(UUID.randomUUID(), clock.instant().minusSeconds(1));

        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(InvalidDemoTokenException.class)
                .hasMessage("Invalid or expired demo session");
    }

    @Test
    void rejectsAWeakSigningSecretAtStartup() {
        assertThatThrownBy(() -> new DemoTokenService("short", clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Demo session secret must be at least 32 characters");
    }
}
