package com.harishdarko.caselens.demo;

import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

interface DemoWorkspaceRepository extends JpaRepository<DemoWorkspace, UUID> {
    boolean existsByIdAndExpiresAtAfter(UUID id, Instant now);
}
