package com.harishdarko.caselens.demo;

import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DemoWorkspaceRepository extends JpaRepository<DemoWorkspace, UUID> {
    boolean existsByIdAndExpiresAtAfter(UUID id, Instant now);

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
