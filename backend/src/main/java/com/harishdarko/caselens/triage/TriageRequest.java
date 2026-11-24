package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.ticket.TicketChannel;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One ticket to triage. The two suppliers describe the delivery being processed (both null outside the worker, where
 * the provider's own call budget applies). They are read when a provider needs them, never snapshotted earlier, so
 * time spent on database calls before the provider call is counted:
 * <ul>
 * <li>{@code timeBudget}: the most the provider call may take now, from the worker Lambda's remaining time less the
 * reserve for what follows the call. It can be zero or negative.</li>
 * <li>{@code sinceReceived}: how long ago the queue handed this delivery to the worker, which is when SQS's
 * visibility clock started.</li>
 * </ul>
 */
public record TriageRequest(UUID ticketId, String subject, String message, TicketChannel channel,
        int repeatContactCount, PackRules pack, Supplier<Duration> timeBudget, Supplier<Duration> sinceReceived) {
    /**
     * The least time budget worth a provider call: the connect allowance plus the minimum first timeout, which both
     * providers share (GroqTriageProvider and GeminiTriageProvider keep the same two constants). Below it, no call is
     * made and no daily AI budget is spent.
     */
    static final Duration MIN_ATTEMPT_BUDGET = Duration.ofSeconds(3);
    static final String TIME_BUDGET_EXHAUSTED = "TIME_BUDGET_EXHAUSTED";

    public TriageRequest {
        Objects.requireNonNull(pack, "Industry pack is required");
        Objects.requireNonNull(ticketId, "Ticket id is required");
        subject = bounded(subject, "Subject", 5, 160);
        message = bounded(message, "Message", 20, 5000);
        Objects.requireNonNull(channel, "Ticket channel is required");
        if (repeatContactCount < 0) throw new IllegalArgumentException("Repeat contact count cannot be negative");
    }

    public TriageRequest(UUID ticketId, String subject, String message, TicketChannel channel, int repeatContactCount,
            PackRules pack) {
        this(ticketId, subject, message, channel, repeatContactCount, pack, null, null);
    }

    /** A request triaged with the default industry pack. */
    public TriageRequest(UUID ticketId, String subject, String message, TicketChannel channel, int repeatContactCount) {
        this(ticketId, subject, message, channel, repeatContactCount, PackRules.evCharging());
    }

    /** The same ticket, for one delivery of a queued job; both suppliers are read live (see the class comment). */
    public TriageRequest withCallContext(Supplier<Duration> timeBudget, Supplier<Duration> sinceReceived) {
        return new TriageRequest(ticketId, subject, message, channel, repeatContactCount, pack, timeBudget, sinceReceived);
    }

    /** The same ticket with a fixed time budget for the provider call. */
    public TriageRequest withTimeBudget(Duration budget) {
        Objects.requireNonNull(budget);
        return withCallContext(() -> budget, sinceReceived);
    }

    /** The provider's own budget, cut to the worker's when that is shorter. Read now; it may be zero or negative. */
    Duration callBudget(Duration providerBudget) {
        if (timeBudget == null) return providerBudget;
        Duration workerBudget = timeBudget.get();
        return workerBudget.compareTo(providerBudget) >= 0 ? providerBudget : workerBudget;
    }

    /** How long since the queue handed over this delivery; {@code providerElapsed} (time in this call) when unknown. */
    Duration sinceReceived(Duration providerElapsed) {
        return sinceReceived == null ? providerElapsed : sinceReceived.get();
    }

    /** True when the time budget, read now, cannot fit one useful attempt; a request without a budget always can. */
    boolean outOfTime() {
        return timeBudget != null && timeBudget.get().compareTo(MIN_ATTEMPT_BUDGET) < 0;
    }

    private static String bounded(String value, String label, int minimum, int maximum) {
        Objects.requireNonNull(value, label + " is required");
        if (value.isBlank() || value.length() < minimum || value.length() > maximum) {
            throw new IllegalArgumentException(label + " must contain " + minimum + " to " + maximum + " characters");
        }
        return value;
    }
}
