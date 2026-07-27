package com.harishdarko.caselens.demo;

import java.time.Instant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
class DemoSessionController {
    private final DemoSessionService sessions;

    DemoSessionController(DemoSessionService sessions) { this.sessions = sessions; }

    @PostMapping("/session")
    SessionResponse create() {
        DemoSessionService.DemoSession session = sessions.create();
        return new SessionResponse(session.token(), session.expiresAt());
    }

    record SessionResponse(String token, Instant expiresAt) {}
}
