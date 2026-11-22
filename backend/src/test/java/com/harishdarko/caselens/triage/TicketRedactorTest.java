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
        assertThat(redactor.redact("Subject x", "My RFID tag 9F8E7D6C stopped working").message())
                .isEqualTo("My RFID tag [RFID_1] stopped working");
        assertThat(redactor.redact("Subject x", "The RFID chip AB12CD34 and RFID fob 77FF0011 fail").message())
                .isEqualTo("The RFID chip [RFID_1] and RFID fob [RFID_2] fail");
        assertThat(redactor.redact("Subject x", "RFID id 0011AABB is blocked").message())
                .isEqualTo("RFID id [RFID_1] is blocked");
    }

    @Test
    void redactsAValueAfterIsOrAColon() {
        assertThat(redactor.redact("Subject x", "My payment id is 12345678 and it failed").message())
                .isEqualTo("My payment id is [PAYMENT_ID_1] and it failed");
        assertThat(redactor.redact("Subject x", "Transaction id: TX-55AA failed").message())
                .isEqualTo("Transaction id [TRANSACTION_ID_1] failed");
    }

    @Test
    void redactsIdentifiersWithoutDigitsAsMainDid() {
        assertThat(redactor.redact("Subject x", "Card RFID ABCDEFGH was declined").message())
                .isEqualTo("Card RFID [RFID_1] was declined");
        assertThat(redactor.redact("Subject x", "See transaction id ABCD-EFGH for details").message())
                .isEqualTo("See transaction id [TRANSACTION_ID_1] for details");
        assertThat(redactor.redact("Subject x", "Stripe payment id pi_3NxYzAbCdEf failed").message())
                .isEqualTo("Stripe payment id [PAYMENT_ID_1] failed");
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
    void leavesPlainWordsAfterALabelAlone() {
        assertThat(redactor.redact("RFID card is rejected", "The payment id missing from the receipt was never sent.").subject())
                .isEqualTo("RFID card is rejected");
        assertThat(redactor.redact("Subject x", "The payment id missing from the receipt was never sent.").message())
                .isEqualTo("The payment id missing from the receipt was never sent.");
        assertThat(redactor.redact("Subject x", "Your RFID card is rejected and the RFID token expired.").message())
                .isEqualTo("Your RFID card is rejected and the RFID token expired.");
    }

    @Test
    void closesTheRoundTwoLeaks() {
        // CL2-07 probe strings: a separator before the descriptor, a comma, lowercase values after a colon.
        assertThat(redactor.redact("Subject x", "payment id: ref PAY-99821 failed").message())
                .isEqualTo("payment id ref [PAYMENT_ID_1] failed");
        assertThat(redactor.redact("Subject x", "RFID, card 04A1B2C3D4 was declined").message())
                .isEqualTo("RFID card [RFID_1] was declined");
        assertThat(redactor.redact("Subject x", "RFID: deadbeef was declined").message())
                .isEqualTo("RFID [RFID_1] was declined");
        assertThat(redactor.redact("Subject x", "payment id: abcdefgh was declined").message())
                .isEqualTo("payment id [PAYMENT_ID_1] was declined");
        assertThat(redactor.redact("Subject x", "My RFID deadbeef01 stopped").message())
                .isEqualTo("My RFID [RFID_1] stopped");
        // Already redacted before this change (the reviewer's first example was wrong); it stays redacted.
        assertThat(redactor.redact("Subject x", "RFID: token RFID-DEMO-A19 failed").message())
                .isEqualTo("RFID token [RFID_1] failed")
                .doesNotContain("A19");
    }

    @Test
    void redactsTheSeededPaymentReferenceFixtures() {
        assertThat(redactor.redact("Subject x", "connector 2 accepted payment reference PAY-DEMO-7319 for session "
                + "SESSION-DEMO-88421 at 14:06 UTC").message())
                .isEqualTo("connector 2 accepted payment reference [PAYMENT_ID_1] for session SESSION-DEMO-88421 at 14:06 UTC");
        assertThat(redactor.redact("Subject x", "the statement contains payment references PAY-DEMO-1004 and "
                + "PAY-DEMO-1005 for the same CAD 18.40 amount.").message())
                .isEqualTo("the statement contains payment references [PAYMENT_ID_1] and [PAYMENT_ID_2] for the same CAD 18.40 amount.");
        assertThat(redactor.redact("Subject x", "payment ref PAY-1001, PAY-1002 or PAY-1001 at CHG-CA-1042").message())
                .isEqualTo("payment ref [PAYMENT_ID_1], [PAYMENT_ID_2] or [PAYMENT_ID_1] at CHG-CA-1042");
        // A listed value from another family is not swept up with the payment reference.
        assertThat(redactor.redact("Subject x", "payment reference PAY-DEMO-7319 and CHG-CA-1042 failed").message())
                .isEqualTo("payment reference [PAYMENT_ID_1] and CHG-CA-1042 failed");
    }

    @Test
    void stillLeavesPlainWordsAfterADescriptorOrIsAlone() {
        assertThat(redactor.redact("Subject x", "RFID: card rejected at the site").message())
                .isEqualTo("RFID: card rejected at the site");
        assertThat(redactor.redact("Subject x", "The payment reference was missing").message())
                .isEqualTo("The payment reference was missing");
    }

    @Test
    void doesNotRedactOrdinaryChargerAndSessionReferences() {
        RedactedTicket redacted = redactor.redact(
                "Session failed",
                "Charger CHG-CA-1042 did not start SESSION-DEMO-88421 on connector 2.");

        assertThat(redacted.message()).contains("CHG-CA-1042", "SESSION-DEMO-88421", "connector 2");
    }
}
