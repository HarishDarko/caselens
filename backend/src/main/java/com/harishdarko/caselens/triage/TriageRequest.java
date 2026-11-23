package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.ticket.TicketChannel;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * One ticket to triage. {@code eventId} is the queued job's event (null outside the worker), so the AI budget is
 * charged once per job however often SQS delivers it. {@code timeBudget} is the most the provider call may take, from
 * the worker Lambda's remaining time (null when unknown: the provider's own call budget applies).
 */
public record TriageRequest(UUID ticketId, String subject, String message, TicketChannel channel,
        int repeatContactCount, PackRules pack, UUID eventId, Duration timeBudget) {
    public TriageRequest {
        Objects.requireNonNull(pack, "Industry pack is required");
        Objects.requireNonNull(ticketId, "Ticket id is required");
        subject = bounded(subject, "Subject", 5, 160);
        message = bounded(message, "Message", 20, 5000);
        Objects.requireNonNull(channel, "Ticket channel is required");
        if (repeatContactCount < 0) throw new IllegalArgumentException("Repeat contact count cannot be negative");
        if (timeBudget != null && timeBudget.isNegative()) timeBudget = Duration.ZERO;
    }

    public TriageRequest(UUID ticketId, String subject, String message, TicketChannel channel, int repeatContactCount,
            PackRules pack) {
        this(ticketId, subject, message, channel, repeatContactCount, pack, null, null);
    }

    /** A request triaged with the default industry pack. */
    public TriageRequest(UUID ticketId, String subject, String message, TicketChannel channel, int repeatContactCount) {
        this(ticketId, subject, message, channel, repeatContactCount, PackRules.evCharging());
    }

    /** The same ticket, for one delivery of a queued job. */
    public TriageRequest withCallContext(UUID eventId, Duration timeBudget) {
        return new TriageRequest(ticketId, subject, message, channel, repeatContactCount, pack, eventId, timeBudget);
    }

    /** The provider's own budget, cut to the worker's when that is shorter. */
    Duration callBudget(Duration providerBudget) {
        return timeBudget == null || timeBudget.compareTo(providerBudget) >= 0 ? providerBudget : timeBudget;
    }

    private static String bounded(String value, String label, int minimum, int maximum) {
        Objects.requireNonNull(value, label + " is required");
        if (value.isBlank() || value.length() < minimum || value.length() > maximum) {
            throw new IllegalArgumentException(label + " must contain " + minimum + " to " + maximum + " characters");
        }
        return value;
    }
}
