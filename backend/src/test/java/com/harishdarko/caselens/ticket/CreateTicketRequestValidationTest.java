package com.harishdarko.caselens.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** CL2-02: the create limits hold after surrounding whitespace is stripped, not just on the raw length. */
class CreateTicketRequestValidationTest {
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final String MESSAGE = "1. Every charger at the site shows offline since this morning.";

    @Test
    void rejectsASubjectThatIsShortOnceItsPaddingIsStripped() {
        assertThat(fields(new TicketController.CreateTicketRequest("  OK  ", MESSAGE, TicketChannel.WEB)))
                .containsExactly("subject");
        assertThat(fields(new TicketController.CreateTicketRequest("Hi   ", MESSAGE, TicketChannel.WEB)))
                .containsExactly("subject");
    }

    @Test
    void rejectsAMessageThatIsShortOnceItsPaddingIsStripped() {
        assertThat(fields(new TicketController.CreateTicketRequest("Charger offline", "ok" + " ".repeat(25),
                TicketChannel.WEB))).containsExactly("message");
    }

    @Test
    void acceptsPaddedTextThatIsLongEnoughOnceStripped() {
        assertThat(fields(new TicketController.CreateTicketRequest("  Charger offline  ", "\n" + MESSAGE + "\n\n",
                TicketChannel.WEB))).isEmpty();
        assertThat(fields(new TicketController.CreateTicketRequest("A, B!", "Line one.\nLine two is here.",
                TicketChannel.CHAT))).isEmpty();
    }

    private static Set<String> fields(TicketController.CreateTicketRequest request) {
        Set<ConstraintViolation<TicketController.CreateTicketRequest>> violations = VALIDATOR.validate(request);
        return violations.stream().map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }
}
