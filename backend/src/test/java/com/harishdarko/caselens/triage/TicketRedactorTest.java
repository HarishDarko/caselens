package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TicketRedactorTest {
    private final TicketRedactor redactor = new TicketRedactor();

    @Test
    void usesStablePlaceholdersWithoutChangingTheSourceInput() {
        String subject = "Contact alex@example.com about payment ID PAY-99821";
        String message = "alex@example.com called +1 416-555-0188. Card 4111 1111 1111 1111. "
                + "Payment ID PAY-99821 and RFID RFID-DEMO-A19 were rejected.";

        RedactedTicket redacted = redactor.redact(subject, message);

        assertThat(redacted.subject()).contains("[EMAIL_1]", "[PAYMENT_ID_1]");
        assertThat(redacted.message()).contains("[EMAIL_1]", "[PHONE_1]", "[CARD_1]", "[PAYMENT_ID_1]", "[RFID_1]");
        assertThat(subject).isEqualTo("Contact alex@example.com about payment ID PAY-99821");
        assertThat(message).contains("4111 1111 1111 1111");
    }

    @Test
    void doesNotRedactOrdinaryChargerAndSessionReferences() {
        RedactedTicket redacted = redactor.redact(
                "Session failed",
                "Charger CHG-CA-1042 did not start SESSION-DEMO-88421 on connector 2.");

        assertThat(redacted.message()).contains("CHG-CA-1042", "SESSION-DEMO-88421", "connector 2");
    }
}
