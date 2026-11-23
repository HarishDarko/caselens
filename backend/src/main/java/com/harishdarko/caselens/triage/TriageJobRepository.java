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

    /** The job's ticket id as a column read, so the job itself does not enter the persistence context. */
    @Query("select job.ticketId from TriageJob job where job.id = :id and job.workspaceId = :workspaceId")
    Optional<UUID> findTicketIdByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") UUID workspaceId);
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

    /**
     * Failures a reviewer may replay: terminal ones, the acknowledged synthetic demo failure, retryable ones that SQS
     * has stopped redelivering (last updated before sqsGaveUpBefore, see FailureRecoveryService.SQS_REDELIVERY_WINDOW),
     * and stranded QUEUED or PROCESSING jobs not updated for that long. A stranded job's message went to the DLQ
     * after maxReceiveCount failed deliveries (for example the database was unreachable), so nothing else will move it.
     * A PROCESSING job's claim lease is far shorter than the window, so such a job has no live worker either.
     */
    @Query("""
            select job from TriageJob job
            where job.workspaceId = :workspaceId
              and (job.status = com.harishdarko.caselens.triage.TriageJobStatus.TERMINAL_FAILURE
                   or (job.status = com.harishdarko.caselens.triage.TriageJobStatus.RETRYABLE_FAILURE
                       and (job.lastErrorCode = 'SYNTHETIC_PROVIDER_TIMEOUT' or job.updatedAt < :sqsGaveUpBefore))
                   or (job.status in (com.harishdarko.caselens.triage.TriageJobStatus.QUEUED,
                                      com.harishdarko.caselens.triage.TriageJobStatus.PROCESSING)
                       and job.updatedAt < :sqsGaveUpBefore))
              and not exists (
                  select replacement.id from TriageJob replacement
                  where replacement.replayedFromJobId = job.id
              )
            """)
    Page<TriageJob> findRecoverableFailures(@Param("workspaceId") UUID workspaceId,
            @Param("sqsGaveUpBefore") Instant sqsGaveUpBefore, Pageable pageable);

    /** True when a reviewer replay has already queued a replacement for this job. */
    boolean existsByReplayedFromJobId(UUID replayedFromJobId);

    List<TriageJob> findByWorkspaceId(UUID workspaceId);

    /**
     * How long a claim protects a job. The worker commits its claim before the AI call, so a worker that dies during
     * the call leaves the job in PROCESSING. The claim is made after the invocation starts and the worker Lambda
     * timeout is 60 s, so 62 s after the claim the first worker is gone. A delivery that finds a live claim is not
     * dropped: the worker returns RETRY (CLAIM_HELD), SQS shows the message again after its 90 s visibility timeout,
     * and that delivery sees the job COMPLETED (a duplicate) or the lease lapsed (it takes the job). maxReceiveCount 5
     * bounds the retries. A short lease leaves more of the 90 s for the time between receive and claim.
     */
    Duration CLAIM_LEASE = Duration.ofSeconds(62);

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

    /**
     * Closes a stranded QUEUED or PROCESSING job before a reviewer replay queues its replacement. It is one
     * conditional UPDATE, so it only changes a job that is still stranded when the row is locked: not updated since
     * staleBefore and, when PROCESSING, with a claim lease that ended before leaseEndedBefore. A worker that claims
     * the job first makes this return 0 (the replay then returns the job unchanged). Once it returns 1, the job is
     * TERMINAL_FAILURE, so a late or redriven original message fails the claim and is acknowledged as a duplicate
     * before any provider call (TriageWorker.claimNotTaken). It also takes the job out of the active-job unique index,
     * which would otherwise block the replacement. It keeps the persistence context, so the locked ticket the replay
     * holds stays managed.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE triage_job
               SET status = 'TERMINAL_FAILURE', last_error_code = :errorCode, updated_at = :now
             WHERE id = :jobId
               AND updated_at < :staleBefore
               AND (status = 'QUEUED' OR (status = 'PROCESSING' AND started_at < :leaseEndedBefore))
            """, nativeQuery = true)
    int closeStranded(@Param("jobId") UUID jobId, @Param("errorCode") String errorCode, @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore, @Param("leaseEndedBefore") Instant leaseEndedBefore);

    /**
     * Guard for the worker's final writes: matches only while the job is still PROCESSING under the attempt this worker
     * claimed. It returns 0 when the lease lapsed and another worker took the job (or finished it). When it returns 1,
     * the row stays locked until the surrounding transaction commits, so no claim can slip in before the final saves.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE triage_job
               SET updated_at = :now
             WHERE event_id = :eventId
               AND status = 'PROCESSING'
               AND attempt_count = :claimedAttempt
            """, nativeQuery = true)
    int holdClaim(@Param("eventId") UUID eventId, @Param("claimedAttempt") int claimedAttempt,
            @Param("now") Instant now);
}
