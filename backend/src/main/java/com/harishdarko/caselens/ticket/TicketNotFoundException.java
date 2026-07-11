package com.harishdarko.caselens.ticket;

public final class TicketNotFoundException extends RuntimeException {
    public TicketNotFoundException() { super("Ticket not found"); }
}
