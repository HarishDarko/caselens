package com.harishdarko.caselens.demo;

import java.time.Instant;
import java.util.UUID;

public record DemoPrincipal(UUID workspaceId, Instant expiresAt) {}
