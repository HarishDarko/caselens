package com.harishdarko.caselens.triage;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TriageResultRepository extends JpaRepository<TriageResult, UUID> {
    Optional<TriageResult> findFirstByTicketIdAndWorkspaceIdOrderByCreatedAtDesc(UUID ticketId, UUID workspaceId);
    Optional<TriageResult> findByEventId(UUID eventId);
    Optional<TriageResult> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
