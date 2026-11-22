package com.harishdarko.caselens.triage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    /**
     * Unpublished events, fewest publish attempts first, then oldest. Every unpublished event stays a candidate, so
     * none is ever abandoned; the ordering keeps a row that keeps failing behind newer events. Takes no lock; see
     * lockUnpublished.
     */
    @Query(value = """
            SELECT * FROM outbox_event
             WHERE published_at IS NULL
             ORDER BY publish_attempts, created_at, id
             LIMIT :limit
            """, nativeQuery = true)
    List<OutboxEvent> findPublishCandidates(@Param("limit") int limit);

    /** Locks one still-unpublished event for the caller's transaction, or returns empty if another relay holds it. */
    @Query(value = "SELECT * FROM outbox_event WHERE id = :id AND published_at IS NULL FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<OutboxEvent> lockUnpublished(@Param("id") UUID id);

    /** Marks an event published. A row that is already published is left as it is. */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE outbox_event SET published_at = :now WHERE id = :id AND published_at IS NULL", nativeQuery = true)
    int markPublished(@Param("id") UUID id, @Param("now") Instant now);

    /** Counts a failed publish. Never touches a row that another path has published in the meantime. */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_event
               SET publish_attempts = publish_attempts + 1, last_error_code = :errorCode
             WHERE id = :id AND published_at IS NULL
            """, nativeQuery = true)
    int recordPublishFailure(@Param("id") UUID id, @Param("errorCode") String errorCode);
}
