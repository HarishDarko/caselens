package com.harishdarko.caselens.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
class DemoSessionController {
    private final DemoSessionService sessions;

    DemoSessionController(DemoSessionService sessions) { this.sessions = sessions; }

    @PostMapping("/session")
    SessionResponse create(@Valid @RequestBody SessionRequest request) {
        DemoSessionService.DemoSession session = sessions.create(request.passcode());
        return new SessionResponse(session.token(), session.expiresAt());
    }

    record SessionRequest(@NotBlank String passcode) {}
    record SessionResponse(String token, Instant expiresAt) {}
}
