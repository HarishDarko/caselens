package com.harishdarko.caselens.demo;

import com.harishdarko.caselens.ticket.TicketChannel;

public record DemoScenario(String key, String subject, String message, TicketChannel channel,
        String expectedCategory, String expectedUrgency) {}
