package com.harishdarko.caselens.common;

import com.harishdarko.caselens.demo.InvalidPasscodeException;
import com.harishdarko.caselens.ticket.TicketNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(InvalidPasscodeException.class)
    ResponseEntity<ProblemDetail> unauthorized() {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", "The passcode was not accepted");
    }

    @ExceptionHandler(TicketNotFoundException.class)
    ResponseEntity<ProblemDetail> ticketNotFound() {
        return problem(HttpStatus.NOT_FOUND, "Not found", "Ticket not found");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalidArgument(IllegalArgumentException exception) {
        return problem(HttpStatus.CONFLICT, "Invalid operation", "The requested operation is not valid");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> validation(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "The request did not satisfy the API contract");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        return ResponseEntity.status(status).body(body);
    }
}
