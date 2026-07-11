package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.ticket.TicketChannel;
import java.util.Objects;
import java.util.UUID;

public record TriageRequest(UUID ticketId, String subject, String message, TicketChannel channel,
        int repeatContactCount) {
    public TriageRequest {
        Objects.requireNonNull(ticketId, "Ticket id is required");
        subject = bounded(subject, "Subject", 5, 160);
        message = bounded(message, "Message", 20, 5000);
        Objects.requireNonNull(channel, "Ticket channel is required");
        if (repeatContactCount < 0) throw new IllegalArgumentException("Repeat contact count cannot be negative");
    }

    private static String bounded(String value, String label, int minimum, int maximum) {
        Objects.requireNonNull(value, label + " is required");
        if (value.isBlank() || value.length() < minimum || value.length() > maximum) {
            throw new IllegalArgumentException(label + " must contain " + minimum + " to " + maximum + " characters");
        }
        return value;
    }
}
