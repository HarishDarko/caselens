package com.harishdarko.caselens.ticket;

import com.harishdarko.caselens.demo.DemoPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
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

    TicketController(TicketService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TicketResponse create(@AuthenticationPrincipal DemoPrincipal principal,
            @Valid @RequestBody CreateTicketRequest request) {
        return TicketResponse.from(service.create(principal.workspaceId(), request.subject(), request.message(), request.channel()));
    }

    @GetMapping("/{ticketId}")
    TicketResponse get(@AuthenticationPrincipal DemoPrincipal principal, @PathVariable UUID ticketId) {
        return TicketResponse.from(service.get(principal.workspaceId(), ticketId));
    }

    @GetMapping
    TicketPageResponse list(@AuthenticationPrincipal DemoPrincipal principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) TicketChannel channel) {
        return TicketPageResponse.from(service.list(principal.workspaceId(), page, size, status, channel));
    }

    record CreateTicketRequest(
            @NotNull @Size(min = 5, max = 160) String subject,
            @NotNull @Size(min = 20, max = 5000) String message,
            @NotNull TicketChannel channel) {}

    record TicketResponse(UUID id, String displayId, String subject, String message, TicketChannel channel,
            TicketStatus status, String scenarioKey, Instant createdAt, Instant updatedAt) {
        static TicketResponse from(Ticket ticket) {
            return new TicketResponse(ticket.getId(), ticket.getDisplayId(), ticket.getSubject(), ticket.getMessage(),
                    ticket.getChannel(), ticket.getStatus(), ticket.getScenarioKey(), ticket.getCreatedAt(), ticket.getUpdatedAt());
        }
    }

    record TicketPageResponse(List<TicketResponse> content, int page, int size, long totalElements, int totalPages) {
        static TicketPageResponse from(Page<Ticket> tickets) {
            return new TicketPageResponse(tickets.map(TicketResponse::from).getContent(), tickets.getNumber(),
                    tickets.getSize(), tickets.getTotalElements(), tickets.getTotalPages());
        }
    }
}
