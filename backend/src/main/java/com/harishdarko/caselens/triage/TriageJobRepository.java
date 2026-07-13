package com.harishdarko.caselens.triage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TriageJobRepository extends JpaRepository<TriageJob, UUID> {
    Optional<TriageJob> findByEventId(UUID eventId);
    Optional<TriageJob> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    Optional<TriageJob> findFirstByTicketIdAndWorkspaceIdAndContentVersionOrderByCreatedAtDesc(
            UUID ticketId, UUID workspaceId, int contentVersion);
    Page<TriageJob> findByWorkspaceIdAndStatusIn(UUID workspaceId, List<TriageJobStatus> statuses, Pageable pageable);

    @Query("""
            select job from TriageJob job
            where job.workspaceId = :workspaceId
              and job.status in :statuses
              and not exists (
                  select replacement.id from TriageJob replacement
                  where replacement.replayedFromJobId = job.id
              )
            """)
    Page<TriageJob> findUnreplayedFailures(UUID workspaceId, List<TriageJobStatus> statuses, Pageable pageable);

    @Query("""
            select job from TriageJob job
            where job.workspaceId = :workspaceId
              and (job.status = com.harishdarko.caselens.triage.TriageJobStatus.TERMINAL_FAILURE
                   or (job.status = com.harishdarko.caselens.triage.TriageJobStatus.RETRYABLE_FAILURE
                       and job.lastErrorCode = 'SYNTHETIC_PROVIDER_TIMEOUT'))
              and not exists (
                  select replacement.id from TriageJob replacement
                  where replacement.replayedFromJobId = job.id
              )
            """)
    Page<TriageJob> findRecoverableFailures(UUID workspaceId, Pageable pageable);

    List<TriageJob> findByWorkspaceId(UUID workspaceId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE triage_job
               SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                   started_at = :now, updated_at = :now
             WHERE event_id = :eventId
               AND status IN ('QUEUED', 'RETRYABLE_FAILURE')
            """, nativeQuery = true)
    int claimForProcessing(@Param("eventId") UUID eventId, @Param("now") java.time.Instant now);
}
