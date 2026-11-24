package com.harishdarko.caselens.demo;

import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketService;
import com.harishdarko.caselens.triage.LatestTriageReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoSessionService {
    private static final Duration SESSION_TTL = Duration.ofHours(24);
    private final DemoWorkspaceRepository workspaces;
    private final DemoTokenService tokens;
    private final Clock clock;
    private final TicketService tickets;
    private final RuntimeSummary runtime;
    private final LatestTriageReader triage;

    DemoSessionService(DemoWorkspaceRepository workspaces, DemoTokenService tokens, Clock clock, TicketService tickets,
            RuntimeSummary runtime, LatestTriageReader triage) {
        this.workspaces = workspaces;
        this.tokens = tokens;
        this.clock = clock;
        this.tickets = tickets;
        this.runtime = runtime;
        this.triage = triage;
    }

    @Transactional
    public DemoSession create() {
        return create(DemoWorkspace.DEFAULT_INDUSTRY);
    }

    /** A new 24-hour workspace for the given industry; the caller checks that the industry exists. */
    @Transactional
    public DemoSession create(String industry) {
        return create(industry, null);
    }

    /**
     * A new 24-hour workspace for the given industry, opened by the client with this key. Its AI calls count against
     * that client's daily share of the AI budget; a null key counts against the global budget only.
     */
    @Transactional
    public DemoSession create(String industry, String clientKey) {
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = createdAt.plus(SESSION_TTL);
        UUID workspaceId = UUID.randomUUID();
        workspaces.save(new DemoWorkspace(workspaceId, createdAt, expiresAt, industry, clientKey));
        return new DemoSession(workspaceId, tokens.create(workspaceId, expiresAt), expiresAt, industry);
    }

    /**
     * Everything the page shows when a workspace opens: a new workspace, its demo tickets seeded exactly as
     * {@code POST /api/demo/reset} does, the first page of {@code GET /api/tickets}, and the runtime summary. The three
     * steps commit one after the other, as the separate requests did, so the list is read back from the database and
     * equals what {@code GET /api/tickets} returns (timestamps at the database's precision).
     */
    public OpenedWorkspace openSeeded(String industry) {
        return openSeeded(industry, null);
    }

    public OpenedWorkspace openSeeded(String industry, String clientKey) {
        DemoSession session = create(industry, clientKey);
        tickets.reset(session.workspaceId());
        List<Ticket> seeded = tickets.list(session.workspaceId(), 0, 50, null, null).getContent();
        // The recorded results come with the tickets, in one read for all of them, so the page opens in one call.
        Map<UUID, LatestTriageReader.Latest> latest = triage.forTickets(session.workspaceId(),
                seeded.stream().map(Ticket::getId).toList());
        return new OpenedWorkspace(session, seeded, runtime, latest);
    }

    public record DemoSession(UUID workspaceId, String token, Instant expiresAt, String industry) {}

    /** latest maps a ticket id to its newest result and review; tickets without a result are not in it. */
    public record OpenedWorkspace(DemoSession session, List<Ticket> tickets, RuntimeSummary runtime,
            Map<UUID, LatestTriageReader.Latest> latest) {}
}
