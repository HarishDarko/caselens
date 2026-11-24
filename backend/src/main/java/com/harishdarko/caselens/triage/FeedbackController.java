package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.demo.DemoPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
class FeedbackController {
    private final FeedbackService service;

    FeedbackController(FeedbackService service) { this.service = service; }

    @PostMapping("/{ticketId}/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    FeedbackResponse submit(@AuthenticationPrincipal DemoPrincipal principal, @PathVariable UUID ticketId,
            @Valid @RequestBody FeedbackRequest request) {
        return FeedbackResponse.from(service.submit(principal.workspaceId(), ticketId, request.resultId(),
                request.decision(), request.category(), request.urgency(), request.slaRisk(), request.note(),
                request.requestId()));
    }

    @GetMapping("/{ticketId}/feedback")
    FeedbackListResponse list(@AuthenticationPrincipal DemoPrincipal principal, @PathVariable UUID ticketId) {
        return new FeedbackListResponse(service.list(principal.workspaceId(), ticketId));
    }

    /**
     * requestId is optional: a client id for this submit, so a resent request saves one entry (CL-05). decision
     * defaults to CORRECTED, as every submit was before it existed; the note is optional for both decisions. The
     * result may be named triageResultId (older clients) or originalResultId (the redesigned page); a request that
     * names two different results is a validation error.
     */
    record FeedbackRequest(UUID triageResultId, UUID originalResultId, FeedbackDecision decision, Category category,
            Urgency urgency, SlaRisk slaRisk, @Size(max = 500) String note, UUID requestId) {
        UUID resultId() {
            if (triageResultId != null && originalResultId != null && !triageResultId.equals(originalResultId)) {
                throw new ConstraintViolationException("The request names two different results", Set.of());
            }
            return triageResultId != null ? triageResultId : originalResultId;
        }
    }

    record FeedbackResponse(UUID id, UUID ticketId, UUID originalResultId, FeedbackDecision decision,
            Category correctedCategory, Urgency correctedUrgency, SlaRisk correctedSlaRisk, String note,
            String submittedBy, Instant createdAt) {
        static FeedbackResponse from(FeedbackSnapshot feedback) {
            return new FeedbackResponse(feedback.id(), feedback.ticketId(), feedback.originalResultId(),
                    feedback.decision(), feedback.correctedCategory(), feedback.correctedUrgency(),
                    feedback.correctedSlaRisk(), feedback.note(), feedback.submittedBy(), feedback.createdAt());
        }
    }

    record FeedbackListResponse(List<FeedbackSnapshot> content) {}
}
