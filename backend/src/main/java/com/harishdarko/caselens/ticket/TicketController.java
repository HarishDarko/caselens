package com.harishdarko.caselens.ticket;

import com.harishdarko.caselens.demo.DemoPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.harishdarko.caselens.triage.LatestTriageReader;
import com.harishdarko.caselens.triage.TriageResultView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/tickets")
class TicketController {
    private final TicketService service;
    private final LatestTriageReader triage;

    TicketController(TicketService service, LatestTriageReader triage) {
        this.service = service;
        this.triage = triage;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TicketResponse create(@AuthenticationPrincipal DemoPrincipal principal,
            @Valid @RequestBody CreateTicketRequest request) {
        return TicketResponse.from(service.create(principal.workspaceId(), request.subject(), request.message(), request.channel()));
    }

    @GetMapping("/{ticketId}")
    TicketResponse get(@AuthenticationPrincipal DemoPrincipal principal, @PathVariable UUID ticketId) {
        Ticket ticket = service.get(principal.workspaceId(), ticketId);
        return TicketResponse.from(ticket, triage.forTickets(principal.workspaceId(), List.of(ticket.getId())).get(ticket.getId()));
    }

    @GetMapping
    TicketPageResponse list(@AuthenticationPrincipal DemoPrincipal principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) TicketChannel channel) {
        Page<Ticket> tickets = service.list(principal.workspaceId(), page, size, status, channel);
        // One read of the newest results and reviews for the whole page, not one per ticket.
        Map<UUID, LatestTriageReader.Latest> latest = triage.forTickets(principal.workspaceId(),
                tickets.getContent().stream().map(Ticket::getId).toList());
        return TicketPageResponse.from(tickets, latest);
    }

    /** At least 5 characters once surrounding whitespace is stripped. */
    static final String VISIBLE_5 = "(?sU)\\s*\\S.{3,}\\S\\s*";
    /** At least 20 characters once surrounding whitespace is stripped. */
    static final String VISIBLE_20 = "(?sU)\\s*\\S.{18,}\\S\\s*";

    /**
     * The length limits also hold after surrounding whitespace is stripped (the patterns), so a padded "  OK  " subject
     * cannot pass @Size on its spaces and leave the rules fallback nothing to quote as evidence.
     */
    record CreateTicketRequest(
            @NotBlank @Size(min = 5, max = 160)
            @Pattern(regexp = VISIBLE_5, message = "must have at least 5 characters besides surrounding whitespace")
            String subject,
            @NotBlank @Size(min = 20, max = 5000)
            @Pattern(regexp = VISIBLE_20, message = "must have at least 20 characters besides surrounding whitespace")
            String message,
            @NotNull TicketChannel channel) {}

    /** latestResult and latestFeedback are null for a ticket that has not been triaged (or reviewed). */
    record TicketResponse(UUID id, String displayId, String subject, String message, TicketChannel channel,
            TicketStatus status, String scenarioKey, Instant createdAt, Instant updatedAt,
            TriageResultView latestResult, LatestTriageReader.LatestFeedback latestFeedback) {
        static TicketResponse from(Ticket ticket) {
            return from(ticket, null);
        }

        static TicketResponse from(Ticket ticket, LatestTriageReader.Latest latest) {
            return new TicketResponse(ticket.getId(), ticket.getDisplayId(), ticket.getSubject(), ticket.getMessage(),
                    ticket.getChannel(), ticket.getStatus(), ticket.getScenarioKey(), ticket.getCreatedAt(), ticket.getUpdatedAt(),
                    latest == null ? null : latest.result(), latest == null ? null : latest.feedback());
        }
    }

    record TicketPageResponse(List<TicketResponse> content, int page, int size, long totalElements, int totalPages) {
        static TicketPageResponse from(Page<Ticket> tickets, Map<UUID, LatestTriageReader.Latest> latest) {
            return new TicketPageResponse(tickets.map(ticket -> TicketResponse.from(ticket, latest.get(ticket.getId()))).getContent(), tickets.getNumber(),
                    tickets.getSize(), tickets.getTotalElements(), tickets.getTotalPages());
        }
    }
}
