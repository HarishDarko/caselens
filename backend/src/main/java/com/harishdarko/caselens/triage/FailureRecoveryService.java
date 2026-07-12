package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.ticket.TicketNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FailureRecoveryService {
    private final TriageJobRepository jobs;
    private final TriageRequestService requests;

    public FailureRecoveryService(TriageJobRepository jobs, TriageRequestService requests) {
        this.jobs = jobs;
        this.requests = requests;
    }

    @Transactional(readOnly = true)
    public Page<TriageJobSnapshot> failures(UUID workspaceId, int page, int size) {
        PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return jobs.findUnreplayedFailures(workspaceId,
                List.of(TriageJobStatus.RETRYABLE_FAILURE, TriageJobStatus.TERMINAL_FAILURE), request)
                .map(TriageJob::snapshot);
    }

    @Transactional
    public TriageJobSnapshot retry(UUID workspaceId, UUID jobId, String correlationId) {
        TriageJob job = jobs.findByIdAndWorkspaceId(jobId, workspaceId)
                .orElseThrow(TicketNotFoundException::new);
        if (job.getStatus() == TriageJobStatus.COMPLETED) {
            throw new IllegalArgumentException("Completed triage jobs cannot be retried");
        }
        if (job.getStatus() != TriageJobStatus.RETRYABLE_FAILURE
                && job.getStatus() != TriageJobStatus.TERMINAL_FAILURE) {
            throw new IllegalArgumentException("Only failed triage jobs can be retried");
        }
        return requests.request(workspaceId, job.getTicketId(), correlationId);
    }
}
