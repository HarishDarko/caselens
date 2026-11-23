package com.harishdarko.caselens.triage;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TriageResultRepository extends JpaRepository<TriageResult, UUID> {
    Optional<TriageResult> findFirstByTicketIdAndWorkspaceIdOrderByCreatedAtDesc(UUID ticketId, UUID workspaceId);
    Optional<TriageResult> findByEventId(UUID eventId);
    Optional<TriageResult> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    /** The workspace's results, oldest first, as ResultFacts: no JSON or long text columns. */
    @Query("""
            select new com.harishdarko.caselens.triage.ResultFacts(result.id, result.eventId, result.ticketId,
                result.category, result.urgency, result.slaRisk, result.decisionSource, result.modelVersion,
                result.createdAt)
            from TriageResult result
            where result.workspaceId = :workspaceId
            order by result.createdAt asc, result.id asc
            """)
    List<ResultFacts> findFactsByWorkspaceId(@Param("workspaceId") UUID workspaceId);
}
