package com.harishdarko.caselens.demo;

import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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

    DemoSessionService(DemoWorkspaceRepository workspaces, DemoTokenService tokens, Clock clock, TicketService tickets,
            RuntimeSummary runtime) {
        this.workspaces = workspaces;
        this.tokens = tokens;
        this.clock = clock;
        this.tickets = tickets;
        this.runtime = runtime;
    }

    @Transactional
    public DemoSession create() {
        return create(DemoWorkspace.DEFAULT_INDUSTRY);
    }

    /** A new 24-hour workspace for the given industry; the caller checks that the industry exists. */
    @Transactional
    public DemoSession create(String industry) {
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = createdAt.plus(SESSION_TTL);
        UUID workspaceId = UUID.randomUUID();
        workspaces.save(new DemoWorkspace(workspaceId, createdAt, expiresAt, industry));
        return new DemoSession(workspaceId, tokens.create(workspaceId, expiresAt), expiresAt, industry);
    }

    /**
     * Everything the page shows when a workspace opens: a new workspace, its demo tickets seeded exactly as
     * {@code POST /api/demo/reset} does, the first page of {@code GET /api/tickets}, and the runtime summary. The three
     * steps commit one after the other, as the separate requests did, so the list is read back from the database and
     * equals what {@code GET /api/tickets} returns (timestamps at the database's precision).
     */
    public OpenedWorkspace openSeeded(String industry) {
        DemoSession session = create(industry);
        tickets.reset(session.workspaceId());
        return new OpenedWorkspace(session, tickets.list(session.workspaceId(), 0, 50, null, null).getContent(), runtime);
    }

    public record DemoSession(UUID workspaceId, String token, Instant expiresAt, String industry) {}

    public record OpenedWorkspace(DemoSession session, List<Ticket> tickets, RuntimeSummary runtime) {}
}
