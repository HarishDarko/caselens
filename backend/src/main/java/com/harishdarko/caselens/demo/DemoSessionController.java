package com.harishdarko.caselens.demo;

import com.harishdarko.caselens.triage.PackCatalog;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
class DemoSessionController {
    private final DemoSessionService sessions;
    private final PackCatalog packs;
    private final DemoScenarios scenarios;
    private final JdbcOperations jdbc;

    DemoSessionController(DemoSessionService sessions, PackCatalog packs, DemoScenarios scenarios, JdbcOperations jdbc) {
        this.sessions = sessions;
        this.packs = packs;
        this.scenarios = scenarios;
        this.jdbc = jdbc;
    }

    /**
     * What a new demo session can be: the industries, with the demo tickets of each. No token needed. The page has the
     * same list in its bundle and calls this in the background when it loads, so this call is also the warm-up: one
     * cheap query wakes the database, and the visitor's first click does not wait for it.
     */
    @GetMapping("/session")
    SessionOptions options() {
        try {
            jdbc.queryForObject("select 1", Integer.class);
        } catch (DataAccessException databaseAsleepOrDown) {
            // The list does not need the database; the first real request will report a database problem.
        }
        List<Industry> industries = packs.ids().stream()
                .map(id -> new Industry(id, packs.require(id).name(), scenario(scenarios.featured(id)),
                        scenarios.forIndustry(id).stream().map(DemoSessionController::scenario).toList()))
                .sorted(Comparator.comparing((Industry industry) -> !industry.id().equals(DemoWorkspace.DEFAULT_INDUSTRY))
                        .thenComparing(Industry::name))
                .toList();
        return new SessionOptions(DemoWorkspace.DEFAULT_INDUSTRY, industries);
    }

    /** Creates a workspace. The body is optional; without an industry the workspace is EV charging, as before. */
    @PostMapping("/session")
    SessionResponse create(@RequestBody(required = false) SessionRequest request) {
        String industry = request == null || request.industry() == null || request.industry().isBlank()
                ? DemoWorkspace.DEFAULT_INDUSTRY : request.industry();
        if (!packs.ids().contains(industry)) throw new ConstraintViolationException("Unknown industry", Set.of());
        DemoSessionService.DemoSession session = sessions.create(industry);
        return new SessionResponse(session.token(), session.expiresAt(), session.industry());
    }

    private static Scenario scenario(DemoScenario scenario) {
        return new Scenario(scenario.key(), scenario.subject());
    }

    record SessionRequest(String industry) {}

    record SessionResponse(String token, Instant expiresAt, String industry) {}

    record SessionOptions(String defaultIndustry, List<Industry> industries) {}

    record Industry(String id, String name, Scenario featuredScenario, List<Scenario> scenarios) {}

    record Scenario(String key, String label) {}
}
