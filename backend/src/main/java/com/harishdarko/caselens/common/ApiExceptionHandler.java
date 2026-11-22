package com.harishdarko.caselens.common;

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
    @ExceptionHandler(TicketNotFoundException.class)
    ResponseEntity<ProblemDetail> ticketNotFound() {
        return problem(HttpStatus.NOT_FOUND, "Not found", "Ticket not found");
    }

    /** The problem type and code a client checks to tell a workspace cap apart from other 409 answers. */
    static final java.net.URI WORKSPACE_LIMIT_TYPE = java.net.URI.create("urn:caselens:problem:workspace-limit");
    static final String WORKSPACE_LIMIT_CODE = "workspace-limit";

    @ExceptionHandler(WorkspaceLimitExceededException.class)
    ResponseEntity<ProblemDetail> workspaceLimit(WorkspaceLimitExceededException exception) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.CONFLICT, "Workspace limit reached",
                "This demo workspace holds at most " + exception.limit() + " " + exception.item()
                        + ". Reset the workspace or start a new demo.");
        response.getBody().setType(WORKSPACE_LIMIT_TYPE);
        response.getBody().setProperty("code", WORKSPACE_LIMIT_CODE);
        return response;
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
