package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.demo.DemoPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Validated
@RestController
@RequestMapping("/api/operations")
public class OperationsController {
    private final FailureRecoveryService recovery;

    public OperationsController(FailureRecoveryService recovery) {
        this.recovery = recovery;
    }

    @GetMapping("/failures")
    FailurePageResponse failures(@AuthenticationPrincipal DemoPrincipal principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return FailurePageResponse.from(recovery.failures(principal.workspaceId(), page, size));
    }

    @PostMapping("/failures/{jobId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    RetryResponse retry(@AuthenticationPrincipal DemoPrincipal principal, @PathVariable UUID jobId,
            @RequestHeader(name = "X-Correlation-ID", required = false) String correlationId,
            jakarta.servlet.http.HttpServletResponse response) {
        String effectiveCorrelationId = CorrelationIds.normalize(correlationId);
        response.setHeader("X-Correlation-ID", effectiveCorrelationId);
        TriageJobSnapshot job = recovery.retry(principal.workspaceId(), jobId, effectiveCorrelationId);
        return new RetryResponse(job.jobId(), job.ticketId(), job.status(),
                "/api/tickets/" + job.ticketId() + "/processing");
    }

    record RetryResponse(UUID jobId, UUID ticketId, TriageJobStatus status, String statusUrl) {}

    record FailurePageResponse(List<TriageController.JobResponse> content, int page, int size,
            long totalElements, int totalPages) {
        static FailurePageResponse from(org.springframework.data.domain.Page<TriageJobSnapshot> failures) {
            return new FailurePageResponse(failures.map(TriageController.JobResponse::from).getContent(),
                    failures.getNumber(), failures.getSize(), failures.getTotalElements(), failures.getTotalPages());
        }
    }
}
