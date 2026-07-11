package com.harishdarko.caselens.triage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harishdarko.caselens.demo.DemoPrincipal;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/tickets")
public class TriageController {
    private final TriageRequestService service;
    private final ObjectMapper mapper;

    public TriageController(TriageRequestService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
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
        return ProcessingResponse.from(service.processing(principal.workspaceId(), ticketId), mapper);
    }

    record TriageAcceptedResponse(UUID jobId, UUID ticketId, TriageJobStatus status, String statusUrl) {}

    record ProcessingResponse(JobResponse job, ResultResponse result) {
        static ProcessingResponse from(TriageProcessingSnapshot snapshot, ObjectMapper mapper) {
            return new ProcessingResponse(JobResponse.from(snapshot.job(), snapshot.attempts()),
                    snapshot.result() == null ? null : ResultResponse.from(snapshot.result(), mapper));
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

    record ResultResponse(UUID id, UUID ticketId, Category category, Urgency urgency, SlaRisk slaRisk,
            Sentiment sentiment, String summary, JsonNode evidence, JsonNode policyIds, String explanation,
            JsonNode recommendedActions, String suggestedReply, ReliabilitySignal reliabilitySignal, JsonNode warnings,
            int priorityScore, JsonNode appliedRules, DecisionSource decisionSource, String modelVersion,
            String promptVersion, Instant createdAt) {
        static ResultResponse from(TriageResult result, ObjectMapper mapper) {
            try {
                return new ResultResponse(result.getId(), result.getTicketId(), result.getCategory(), result.getUrgency(),
                        result.getSlaRisk(), result.getSentiment(), result.getSummary(), mapper.readTree(result.getEvidenceJson()),
                        mapper.readTree(result.getPolicyIdsJson()), result.getExplanation(), mapper.readTree(result.getRecommendedActionsJson()),
                        result.getSuggestedReply(), result.getReliabilitySignal(), mapper.readTree(result.getWarningsJson()),
                        result.getPriorityScore(), mapper.readTree(result.getAppliedRulesJson()), result.getDecisionSource(),
                        result.getModelVersion(), result.getPromptVersion(), result.getCreatedAt());
            } catch (IOException exception) {
                throw new IllegalStateException("Stored triage result is not readable", exception);
            }
        }
    }
}
