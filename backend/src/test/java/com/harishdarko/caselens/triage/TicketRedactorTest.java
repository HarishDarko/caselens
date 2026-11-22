package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TicketRedactorTest {
    private final TicketRedactor redactor = new TicketRedactor();

    @Test
    void usesStablePlaceholdersWithoutChangingTheSourceInput() {
        String subject = "Contact reviewer@example.test about payment ID PAY-99821";
        String message = "reviewer@example.test called +1 555-010-0188. Card 4111 1111 1111 1111. "
                + "Payment ID PAY-99821 and RFID RFID-DEMO-A19 were rejected.";

        RedactedTicket redacted = redactor.redact(subject, message);

        assertThat(redacted.subject()).contains("[EMAIL_1]", "[PAYMENT_ID_1]");
        assertThat(redacted.message()).contains("[EMAIL_1]", "[PHONE_1]", "[CARD_1]", "[PAYMENT_ID_1]", "[RFID_1]");
        assertThat(subject).isEqualTo("Contact reviewer@example.test about payment ID PAY-99821");
        assertThat(message).contains("4111 1111 1111 1111");
    }

    @Test
    void redactsTheIdentifierThatFollowsADescriptorWord() {
        assertThat(redactor.redact("Subject x", "My RFID card 04A1B2C3D4 is rejected").message())
                .isEqualTo("My RFID card [RFID_1] is rejected");
        assertThat(redactor.redact("Subject x", "RFID token 04A1B2C3 does not work").message())
                .isEqualTo("RFID token [RFID_1] does not work");
        assertThat(redactor.redact("Subject x", "payment id number PAY12345 failed").message())
                .isEqualTo("payment id number [PAYMENT_ID_1] failed");
        assertThat(redactor.redact("Subject x", "transaction id value TX-998877 failed").message())
                .isEqualTo("transaction id value [TRANSACTION_ID_1] failed");
        assertThat(redactor.redact("Subject x", "Driver badge: RFID badge #77AB12 was declined").message())
                .isEqualTo("Driver badge: RFID badge [RFID_1] was declined");
        assertThat(redactor.redact("Subject x", "Transaction ID reference code TXN-5512 is missing").message())
                .isEqualTo("Transaction ID reference code [TRANSACTION_ID_1] is missing");
    }

    @Test
    void keepsTheSeededRfidTicketReadableAndRedactsItsTokenOnce() {
        RedactedTicket redacted = redactor.redact("Registered RFID card is rejected",
                "Demo account ACCT-DEMO-882 uses RFID token RFID-DEMO-A19. The token worked yesterday but authorization "
                        + "was rejected at CHG-CA-6009 today with status Invalid, while mobile-app authorization still works.");

        assertThat(redacted.subject()).isEqualTo("Registered RFID card is rejected");
        assertThat(redacted.message()).startsWith("Demo account ACCT-DEMO-882 uses RFID token [RFID_1]. The token worked")
                .doesNotContain("RFID-DEMO-A19", "[RFID_2]")
                .contains("CHG-CA-6009");
    }

    @Test
    void leavesWordsWithoutADigitAfterALabelAlone() {
        assertThat(redactor.redact("RFID card is rejected", "The payment id missing from the receipt was never sent.").subject())
                .isEqualTo("RFID card is rejected");
        assertThat(redactor.redact("Subject x", "The payment id missing from the receipt was never sent.").message())
                .isEqualTo("The payment id missing from the receipt was never sent.");
    }

    @Test
    void doesNotRedactOrdinaryChargerAndSessionReferences() {
        RedactedTicket redacted = redactor.redact(
                "Session failed",
                "Charger CHG-CA-1042 did not start SESSION-DEMO-88421 on connector 2.");

        assertThat(redacted.message()).contains("CHG-CA-1042", "SESSION-DEMO-88421", "connector 2");
    }
}
