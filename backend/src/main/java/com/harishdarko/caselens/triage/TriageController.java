package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.demo.DemoPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/tickets")
public class TriageController {
    private final TriageRequestService service;
    private final ObjectMapper mapper;
    private final PackResolver packs;

    public TriageController(TriageRequestService service, ObjectMapper mapper, PackResolver packs) {
        this.service = service;
        this.mapper = mapper;
        this.packs = packs;
    }

    @PostMapping("/{ticketId}/triage")
    @ResponseStatus(HttpStatus.ACCEPTED)
    TriageAcceptedResponse request(@AuthenticationPrincipal DemoPrincipal principal, @PathVariable UUID ticketId,
            @RequestHeader(name = "X-Correlation-ID", required = false) String correlationId,
            HttpServletResponse response) {
        String effectiveCorrelationId = CorrelationIds.normalize(correlationId);
        response.setHeader("X-Correlation-ID", effectiveCorrelationId);
        TriageJobSnapshot job = service.request(principal.workspaceId(), ticketId, effectiveCorrelationId);
        return new TriageAcceptedResponse(job.jobId(), job.ticketId(), job.status(), "/api/tickets/" + ticketId + "/processing");
    }

    @GetMapping("/{ticketId}/processing")
    ProcessingResponse processing(@AuthenticationPrincipal DemoPrincipal principal, @PathVariable UUID ticketId) {
        TriageProcessingSnapshot snapshot = service.processing(principal.workspaceId(), ticketId);
        return ProcessingResponse.from(snapshot,
                snapshot.result() == null ? null : packs.forWorkspace(principal.workspaceId()), mapper);
    }

    record TriageAcceptedResponse(UUID jobId, UUID ticketId, TriageJobStatus status, String statusUrl) {}

    /** {@code job} is null for a ticket that has only a recorded result. */
    record ProcessingResponse(JobResponse job, TriageResultView result) {
        static ProcessingResponse from(TriageProcessingSnapshot snapshot, PackRules pack, ObjectMapper mapper) {
            return new ProcessingResponse(snapshot.job() == null ? null : JobResponse.from(snapshot.job(), snapshot.attempts()),
                    snapshot.result() == null ? null
                            : TriageResultView.from(snapshot.result(), snapshot.fallbackReason(), pack, mapper));
        }
    }

    record JobResponse(UUID id, UUID eventId, UUID ticketId, TriageJobStatus status, int attemptCount,
            Instant startedAt, Instant completedAt, String lastErrorCode, List<TriageAttemptSnapshot> attempts) {
        static JobResponse from(TriageJobSnapshot job) {
            return from(job, List.of());
        }

        static JobResponse from(TriageJobSnapshot job, List<TriageAttemptSnapshot> attempts) {
            return new JobResponse(job.jobId(), job.eventId(), job.ticketId(), job.status(), job.attemptCount(),
                    job.startedAt(), job.completedAt(), job.lastErrorCode(), attempts);
        }
    }
}
