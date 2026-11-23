package com.harishdarko.caselens.ticket;

import java.util.UUID;

/** A ticket's id and scenario key, read without the subject and message columns (the message can be 5 KB). */
public record TicketScenarioKey(UUID id, String scenarioKey) {
    public static TicketScenarioKey of(Ticket ticket) {
        return new TicketScenarioKey(ticket.getId(), ticket.getScenarioKey());
    }
}
