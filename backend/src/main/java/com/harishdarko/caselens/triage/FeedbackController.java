package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.demo.DemoPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
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
        return FeedbackResponse.from(service.submit(principal.workspaceId(), ticketId, request.triageResultId(),
                request.category(), request.urgency(), request.slaRisk(), request.note()));
    }

    @GetMapping("/{ticketId}/feedback")
    FeedbackListResponse list(@AuthenticationPrincipal DemoPrincipal principal, @PathVariable UUID ticketId) {
        return new FeedbackListResponse(service.list(principal.workspaceId(), ticketId));
    }

    record FeedbackRequest(UUID triageResultId, Category category, Urgency urgency, SlaRisk slaRisk,
            @NotBlank @Size(max = 500) String note) {}

    record FeedbackResponse(UUID id, UUID ticketId, UUID originalResultId, Category correctedCategory,
            Urgency correctedUrgency, SlaRisk correctedSlaRisk, String note, String submittedBy, Instant createdAt) {
        static FeedbackResponse from(FeedbackSnapshot feedback) {
            return new FeedbackResponse(feedback.id(), feedback.ticketId(), feedback.originalResultId(),
                    feedback.correctedCategory(), feedback.correctedUrgency(), feedback.correctedSlaRisk(),
                    feedback.note(), feedback.submittedBy(), feedback.createdAt());
        }
    }

    record FeedbackListResponse(List<FeedbackSnapshot> content) {}
}
