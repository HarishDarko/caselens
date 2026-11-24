package com.harishdarko.caselens.triage;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TriageResultRepository extends JpaRepository<TriageResult, UUID> {
    Optional<TriageResult> findFirstByTicketIdAndWorkspaceIdOrderByCreatedAtDescIdDesc(UUID ticketId, UUID workspaceId);
    Optional<TriageResult> findByEventId(UUID eventId);
    Optional<TriageResult> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    /** The workspace's results, oldest first, as ResultFacts: no JSON or long text columns. */
    @Query("""
            select new com.harishdarko.caselens.triage.ResultFacts(result.id, result.eventId, result.ticketId,
                result.category, result.urgency, result.slaRisk, result.decisionSource, result.modelVersion,
                result.createdAt, result.recorded)
            from TriageResult result
            where result.workspaceId = :workspaceId
            order by result.createdAt asc, result.id asc
            """)
    List<ResultFacts> findFactsByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    /**
     * The newest result of each of the given tickets, in one statement: a result is the newest when no other result of
     * the ticket is newer (same ordering as findFirstByTicketId...OrderByCreatedAtDescIdDesc).
     */
    @Query("""
            select result from TriageResult result
            where result.workspaceId = :workspaceId and result.ticketId in :ticketIds
              and not exists (select 1 from TriageResult newer
                  where newer.ticketId = result.ticketId and newer.workspaceId = result.workspaceId
                    and (newer.createdAt > result.createdAt
                         or (newer.createdAt = result.createdAt and newer.id > result.id)))
            """)
    List<TriageResult> findLatestByTicketIds(@Param("workspaceId") UUID workspaceId,
            @Param("ticketIds") java.util.Collection<UUID> ticketIds);
}
