package com.harishdarko.caselens.demo;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DemoWorkspaceRepository extends JpaRepository<DemoWorkspace, UUID> {
    boolean existsByIdAndExpiresAtAfter(UUID id, Instant now);

    /**
     * Locks the workspace row until the transaction ends. On PostgreSQL, Hibernate writes PESSIMISTIC_WRITE as
     * FOR NO KEY UPDATE, which does not block the key-share locks that inserting a child row (a ticket, an outbox
     * event) takes on this row, so it serializes cap checks without deadlocking against a triage request.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from DemoWorkspace w where w.id = :id")
    Optional<DemoWorkspace> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
            SELECT w.client_key FROM demo_workspaces w JOIN tickets t ON t.workspace_id = w.id WHERE t.id = :ticketId
            """, nativeQuery = true)
    Optional<String> findClientKeyByTicketId(@Param("ticketId") UUID ticketId);

    /**
     * Deletes up to {@code limit} expired workspaces in one statement, oldest first. The foreign keys cascade to their
     * tickets, outbox events, jobs, attempts, results, invocations and feedback.
     */
    @Modifying
    @Query(value = """
            DELETE FROM demo_workspaces WHERE id IN (
                SELECT id FROM demo_workspaces WHERE expires_at < :now ORDER BY expires_at LIMIT :limit)
            """, nativeQuery = true)
    int deleteExpiredBatch(@Param("now") Instant now, @Param("limit") int limit);
}
