package com.harishdarko.caselens.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "demo_workspaces")
public class DemoWorkspace {
    /** The industry of workspaces created before industries existed; the same as V6's column default. */
    public static final String DEFAULT_INDUSTRY = "ev-charging";

    @Id private UUID id;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "industry", nullable = false, length = 32) private String industry;
    /** The opener's client key for the AI share (see ClientAddresses.clientKey); null for older workspaces. */
    @Column(name = "client_key", length = 64) private String clientKey;

    protected DemoWorkspace() {}

    public DemoWorkspace(UUID id, Instant createdAt, Instant expiresAt) {
        this(id, createdAt, expiresAt, DEFAULT_INDUSTRY);
    }

    public DemoWorkspace(UUID id, Instant createdAt, Instant expiresAt, String industry) {
        this(id, createdAt, expiresAt, industry, null);
    }

    public DemoWorkspace(UUID id, Instant createdAt, Instant expiresAt, String industry, String clientKey) {
        this.id = id;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.industry = industry;
        this.clientKey = clientKey;
    }

    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getIndustry() { return industry; }
    public String getClientKey() { return clientKey; }
}
