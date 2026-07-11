package com.harishdarko.caselens.ticket;

import com.harishdarko.caselens.demo.DemoScenario;
import com.harishdarko.caselens.demo.ScenarioCatalog;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {
    private final TicketRepository tickets;
    private final ScenarioCatalog scenarios;
    private final Clock clock;

    TicketService(TicketRepository tickets, ScenarioCatalog scenarios, Clock clock) {
        this.tickets = tickets;
        this.scenarios = scenarios;
        this.clock = clock;
    }

    @Transactional
    public Ticket create(UUID workspaceId, String subject, String message, TicketChannel channel) {
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
        return saveScenario(workspaceId, scenarios.require(key));
    }

    @Transactional
    public int reset(UUID workspaceId) {
        tickets.deleteByWorkspaceId(workspaceId);
        List<DemoScenario> all = scenarios.all();
        all.forEach(scenario -> saveScenario(workspaceId, scenario));
        return all.size();
    }

    private Ticket saveScenario(UUID workspaceId, DemoScenario scenario) {
        Ticket ticket = Ticket.create(UUID.randomUUID(), workspaceId, scenario.subject(), scenario.message(),
                scenario.channel(), scenario.key(), clock.instant());
        return tickets.save(ticket);
    }
}
