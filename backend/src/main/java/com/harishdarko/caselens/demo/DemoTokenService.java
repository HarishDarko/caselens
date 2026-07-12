package com.harishdarko.caselens.demo;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public final class DemoTokenService {
    static final String ISSUER = "caselens";
    static final String AUDIENCE = "caselens-demo";
    private final Algorithm algorithm;
    private final Clock clock;

    public DemoTokenService(String secret, Clock clock) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("Demo session secret must be at least 32 characters");
        }
        this.algorithm = Algorithm.HMAC256(secret);
        this.clock = clock;
    }

    public String create(UUID workspaceId, Instant expiresAt) {
        return JWT.create().withSubject(workspaceId.toString()).withIssuer(ISSUER).withAudience(AUDIENCE)
                .withIssuedAt(Date.from(clock.instant())).withExpiresAt(Date.from(expiresAt)).sign(algorithm);
    }

    public DemoPrincipal verify(String token) {
        try {
            DecodedJWT decoded = JWT.decode(token);
            algorithm.verify(decoded);
            Instant now = clock.instant();
            Instant issuedAt = decoded.getIssuedAtAsInstant();
            Instant expiresAt = decoded.getExpiresAtAsInstant();
            if (!ISSUER.equals(decoded.getIssuer())
                    || !List.of(AUDIENCE).equals(decoded.getAudience())
                    || issuedAt == null
                    || issuedAt.isAfter(now.plusSeconds(60))
                    || expiresAt == null
                    || !expiresAt.isAfter(now)) {
                throw new InvalidDemoTokenException();
            }
            return new DemoPrincipal(UUID.fromString(decoded.getSubject()), expiresAt);
        } catch (JWTVerificationException | IllegalArgumentException exception) {
            throw new InvalidDemoTokenException();
        }
    }
}
