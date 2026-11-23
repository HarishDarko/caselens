package com.harishdarko.caselens.ticket;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {
    Optional<Ticket> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    @Query("""
            select new com.harishdarko.caselens.ticket.TicketScenarioKey(t.id, t.scenarioKey) from Ticket t
            where t.workspaceId = :workspaceId and t.id in :ids
            """)
    List<TicketScenarioKey> findScenarioKeys(@Param("workspaceId") UUID workspaceId, @Param("ids") Collection<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.id = :id and t.workspaceId = :workspaceId")
    Optional<Ticket> findByIdAndWorkspaceIdForUpdate(@Param("id") UUID id, @Param("workspaceId") UUID workspaceId);

    long deleteByWorkspaceId(UUID workspaceId);

    long countByWorkspaceId(UUID workspaceId);
}
