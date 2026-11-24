package com.harishdarko.caselens.demo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harishdarko.caselens.demo.DemoDataController.ScenarioTicketResponse;
import com.harishdarko.caselens.security.ClientAddresses;
import com.harishdarko.caselens.triage.PackCatalog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
class DemoSessionController {
    /**
     * At most one wake-up query per instance in this long. Neon suspends after 5 idle minutes, so each wake-up keeps
     * the free compute running for about 6 minutes (the query, the pool's 60 s idle connection, then 5 idle minutes).
     * With a short interval, anything that polls this anonymous GET keeps the database awake for good: always on at
     * 0.25 CU is about 182 CU-hours a month against the free plan's 100. At 30 minutes the worst case is about 6 of
     * every 30 minutes awake per instance, about 36 CU-hours a month, while a visitor arriving after a quiet spell
     * still finds the database waking before the first click. Busy periods do not need it: real requests keep the
     * database awake anyway.
     */
    static final Duration WARM_UP_INTERVAL = Duration.ofMinutes(30);
    private final DemoSessionService sessions;
    private final PackCatalog packs;
    private final DemoScenarios scenarios;
    private final JdbcOperations jdbc;
    private final Clock clock;
    private final ClientAddresses clientAddresses;
    private final AtomicReference<Instant> lastWarmUp = new AtomicReference<>(Instant.MIN);

    DemoSessionController(DemoSessionService sessions, PackCatalog packs, DemoScenarios scenarios, JdbcOperations jdbc,
            Clock clock, ClientAddresses clientAddresses) {
        this.sessions = sessions;
        this.packs = packs;
        this.scenarios = scenarios;
        this.jdbc = jdbc;
        this.clock = clock;
        this.clientAddresses = clientAddresses;
    }

    /**
     * What a new demo session can be: the industries, with the demo tickets of each. No token needed. The page has the
     * same list in its bundle and calls this in the background when it loads, so this call is also the warm-up: one
     * cheap query wakes the database, and the visitor's first click does not wait for it. The query runs at most once
     * per {@link #WARM_UP_INTERVAL} on each instance, so a burst of anonymous calls costs one query, not one each.
     */
    @GetMapping("/session")
    SessionOptions options() {
        Instant now = clock.instant();
        Instant last = lastWarmUp.get();
        if (!now.isBefore(last.plus(WARM_UP_INTERVAL)) && lastWarmUp.compareAndSet(last, now)) {
            try {
                jdbc.queryForObject("select 1", Integer.class);
            } catch (DataAccessException databaseAsleepOrDown) {
                // The list does not need the database; the first real request will report a database problem.
            }
        }
        List<Industry> industries = packs.ids().stream()
                .map(id -> new Industry(id, packs.require(id).name(), scenario(scenarios.featured(id)),
                        scenarios.seededForIndustry(id).stream().map(DemoSessionController::scenario).toList()))
                .sorted(Comparator.comparing((Industry industry) -> !industry.id().equals(DemoWorkspace.DEFAULT_INDUSTRY))
                        .thenComparing(Industry::name))
                .toList();
        return new SessionOptions(DemoWorkspace.DEFAULT_INDUSTRY, industries);
    }

    /**
     * Creates a workspace. The body is optional; without an industry the workspace is EV charging, as before. With
     * {@code seed=true} it also seeds the demo tickets and returns them with the runtime summary, so the page opens a
     * workspace in one call instead of four steps. Without it the answer is the bare session, as before; an API from
     * before this change ignores the parameter in the same way, and the page then seeds through /reset. The workspace
     * records the caller's client key, so its AI calls count against that client's daily share of the AI budget.
     */
    @PostMapping("/session")
    SessionResponse create(@RequestBody(required = false) SessionRequest request,
            @RequestParam(defaultValue = "false") boolean seed, HttpServletRequest httpRequest) {
        String industry = request == null || request.industry() == null || request.industry().isBlank()
                ? DemoWorkspace.DEFAULT_INDUSTRY : request.industry();
        if (!packs.ids().contains(industry)) throw new ConstraintViolationException("Unknown industry", Set.of());
        String clientKey = clientAddresses.clientKey(httpRequest);
        if (!seed) {
            DemoSessionService.DemoSession session = sessions.create(industry, clientKey);
            return new SessionResponse(session.token(), session.expiresAt(), session.industry(), null, null);
        }
        DemoSessionService.OpenedWorkspace opened = sessions.openSeeded(industry, clientKey);
        DemoSessionService.DemoSession session = opened.session();
        return new SessionResponse(session.token(), session.expiresAt(), session.industry(),
                opened.tickets().stream().map(ScenarioTicketResponse::from).toList(), opened.runtime());
    }

    private static Scenario scenario(DemoScenario scenario) {
        return new Scenario(scenario.key(), scenario.subject());
    }

    record SessionRequest(String industry) {}

    /** The tickets and runtime are present only for {@code seed=true}, so the bare session is unchanged. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SessionResponse(String token, Instant expiresAt, String industry, List<ScenarioTicketResponse> tickets,
            RuntimeSummary runtime) {}

    record SessionOptions(String defaultIndustry, List<Industry> industries) {}

    record Industry(String id, String name, Scenario featuredScenario, List<Scenario> scenarios) {}

    record Scenario(String key, String label) {}
}
