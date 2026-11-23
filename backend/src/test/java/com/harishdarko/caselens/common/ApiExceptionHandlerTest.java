package com.harishdarko.caselens.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class ApiExceptionHandlerTest {
    /** A workspace deleted mid-request gets the bearer filter's expired-session answer, not a 500. */
    @Test
    void aWorkspaceThatIsGoneAnswersAsAnExpiredSession() {
        ResponseEntity<ProblemDetail> response = new ApiExceptionHandler().invalidSession();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().getTitle()).isEqualTo("Unauthorized");
        assertThat(response.getBody().getDetail()).isEqualTo("Invalid or expired demo session");
    }
}
