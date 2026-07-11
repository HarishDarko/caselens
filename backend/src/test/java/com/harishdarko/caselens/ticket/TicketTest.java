package com.harishdarko.caselens.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TicketTest {
    @Test
    void derivesAStablePublicDisplayIdFromTheTicketId() {
        UUID id = UUID.fromString("a1b2c3d4-0000-0000-0000-000000000000");

        Ticket ticket = Ticket.create(
                id,
                UUID.randomUUID(),
                "Charging session did not start",
                "Payment completed but the charging session never started.",
                TicketChannel.WEB,
                null,
                Instant.parse("2026-07-11T12:00:00Z"));

        assertThat(ticket.getDisplayId()).isEqualTo("CL-A1B2C3");
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.NEW);
    }

    @Test
    void rejectsInvalidTextEvenWhenCreatedOutsideTheHttpController() {
        assertThatThrownBy(() -> Ticket.create(
                        UUID.randomUUID(), UUID.randomUUID(), "bad", "too short", TicketChannel.WEB, null,
                        Instant.parse("2026-07-11T12:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ticket subject must contain 5 to 160 characters");
    }

    @Test
    void rejectsMissingWorkspaceAndChannelInTheDomainFactory() {
        assertThatThrownBy(() -> Ticket.create(
                        UUID.randomUUID(), null, "Valid subject", "This message is definitely long enough.",
                        TicketChannel.WEB, null, Instant.parse("2026-07-11T12:00:00Z")))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> Ticket.create(
                        UUID.randomUUID(), UUID.randomUUID(), "Valid subject",
                        "This message is definitely long enough.", null, null,
                        Instant.parse("2026-07-11T12:00:00Z")))
                .isInstanceOf(NullPointerException.class);
    }
}
