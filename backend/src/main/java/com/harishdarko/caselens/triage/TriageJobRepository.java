package com.harishdarko.caselens.triage;

import java.time.Duration;
import java.time.Instant;
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

    /**
     * How long a claim protects a job. The worker commits its claim before the AI call, so a worker that dies during
     * the call leaves the job in PROCESSING. The lease is longer than the worker Lambda timeout (60 s), so the first
     * worker is gone, and shorter than the SQS visibility timeout (90 s), so the redelivered message can take the job.
     */
    Duration CLAIM_LEASE = Duration.ofSeconds(75);

    default int claimForProcessing(UUID eventId, Instant now) {
        return claimForProcessing(eventId, now, now.minus(CLAIM_LEASE));
    }

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE triage_job
               SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                   started_at = :now, updated_at = :now
             WHERE event_id = :eventId
               AND (status IN ('QUEUED', 'RETRYABLE_FAILURE')
                    OR (status = 'PROCESSING' AND started_at < :staleBefore))
            """, nativeQuery = true)
    int claimForProcessing(@Param("eventId") UUID eventId, @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore);
}
