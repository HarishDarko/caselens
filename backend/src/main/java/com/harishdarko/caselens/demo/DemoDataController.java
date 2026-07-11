package com.harishdarko.caselens.demo;

import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketChannel;
import com.harishdarko.caselens.ticket.TicketService;
import com.harishdarko.caselens.ticket.TicketStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
class DemoDataController {
    private final TicketService tickets;

    DemoDataController(TicketService tickets) { this.tickets = tickets; }

    @PostMapping("/scenarios/{scenarioKey}")
    @ResponseStatus(HttpStatus.CREATED)
    ScenarioTicketResponse createScenario(@AuthenticationPrincipal DemoPrincipal principal,
            @PathVariable String scenarioKey) {
        return ScenarioTicketResponse.from(tickets.createScenario(principal.workspaceId(), scenarioKey));
    }

    @PostMapping("/reset")
    ResetResponse reset(@AuthenticationPrincipal DemoPrincipal principal) {
        return new ResetResponse(tickets.reset(principal.workspaceId()));
    }

    record ResetResponse(int seeded) {}

    record ScenarioTicketResponse(UUID id, String displayId, String subject, String message, TicketChannel channel,
            TicketStatus status, String scenarioKey, Instant createdAt, Instant updatedAt) {
        static ScenarioTicketResponse from(Ticket ticket) {
            return new ScenarioTicketResponse(ticket.getId(), ticket.getDisplayId(), ticket.getSubject(),
                    ticket.getMessage(), ticket.getChannel(), ticket.getStatus(), ticket.getScenarioKey(),
                    ticket.getCreatedAt(), ticket.getUpdatedAt());
        }
    }
}
