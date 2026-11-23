package com.harishdarko.caselens.ticket;

import com.harishdarko.caselens.common.WorkspaceLimitExceededException;
import com.harishdarko.caselens.demo.DemoScenario;
import com.harishdarko.caselens.demo.DemoScenarios;
import com.harishdarko.caselens.demo.DemoWorkspaceAccess;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {
    private final TicketRepository tickets;
    private final DemoScenarios scenarios;
    private final Clock clock;
    private final DemoWorkspaceAccess workspaces;
    private final int maxTicketsPerWorkspace;

    TicketService(TicketRepository tickets, DemoScenarios scenarios, Clock clock, DemoWorkspaceAccess workspaces,
            @Value("${caselens.limits.tickets-per-workspace:50}") int maxTicketsPerWorkspace) {
        this.tickets = tickets;
        this.scenarios = scenarios;
        this.clock = clock;
        this.workspaces = workspaces;
        this.maxTicketsPerWorkspace = maxTicketsPerWorkspace;
    }

    @Transactional
    public Ticket create(UUID workspaceId, String subject, String message, TicketChannel channel) {
        requireRoom(workspaceId);
        return tickets.save(Ticket.create(UUID.randomUUID(), workspaceId, subject, message, channel, null, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Ticket get(UUID workspaceId, UUID ticketId) {
        return tickets.findByIdAndWorkspaceId(ticketId, workspaceId).orElseThrow(TicketNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Page<Ticket> list(UUID workspaceId, int page, int size, TicketStatus status, TicketChannel channel) {
        Specification<Ticket> scoped = (root, query, builder) -> builder.equal(root.get("workspaceId"), workspaceId);
        if (status != null) scoped = scoped.and((root, query, builder) -> builder.equal(root.get("status"), status));
        if (channel != null) scoped = scoped.and((root, query, builder) -> builder.equal(root.get("channel"), channel));
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return tickets.findAll(scoped, PageRequest.of(page, size, sort));
    }

    @Transactional
    public Ticket createScenario(UUID workspaceId, String key) {
        return createScenario(workspaceId, key, null);
    }

    /**
     * requestId, when the client sends one, becomes the ticket id, so the same request arriving twice (the browser
     * resends it after a throttled 429 it could not read, CL-05) adds one ticket and answers the second arrival
     * with it. The check runs under the workspace lock, before the cap, so a resend never counts as a new ticket.
     */
    @Transactional
    public Ticket createScenario(UUID workspaceId, String key, UUID requestId) {
        workspaces.lockForUpdate(workspaceId);
        if (requestId != null) {
            var earlier = tickets.findByIdAndWorkspaceId(requestId, workspaceId);
            if (earlier.isPresent()) return earlier.get();
            if (tickets.existsById(requestId)) throw new IllegalArgumentException("The request id is already in use");
        }
        DemoScenario scenario = scenarios.require(workspaceId, key);
        requireRoom(workspaceId);
        return saveScenario(workspaceId, scenario, requestId == null ? UUID.randomUUID() : requestId);
    }

    /** Holds the workspace lock, so a reset never interleaves with an add and leaves more tickets than the cap. */
    @Transactional
    public int reset(UUID workspaceId) {
        workspaces.lockForUpdate(workspaceId);
        tickets.deleteByWorkspaceId(workspaceId);
        List<DemoScenario> all = scenarios.forWorkspace(workspaceId);
        all.forEach(scenario -> saveScenario(workspaceId, scenario, UUID.randomUUID()));
        return all.size();
    }

    /**
     * Reset replaces the tickets with the seeded set, so only adding a ticket can reach the cap. The workspace lock,
     * held until the insert commits, makes concurrent adds count one after the other instead of all seeing the same
     * count and passing together.
     */
    private void requireRoom(UUID workspaceId) {
        workspaces.lockForUpdate(workspaceId);
        if (tickets.countByWorkspaceId(workspaceId) >= maxTicketsPerWorkspace) {
            throw new WorkspaceLimitExceededException("tickets", maxTicketsPerWorkspace);
        }
    }

    private Ticket saveScenario(UUID workspaceId, DemoScenario scenario, UUID id) {
        Ticket ticket = Ticket.create(id, workspaceId, scenario.subject(), scenario.message(),
                scenario.channel(), scenario.key(), clock.instant());
        return tickets.save(ticket);
    }
}
