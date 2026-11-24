package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.ticket.Ticket;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Gives a freshly seeded workspace its reviewed examples: for each seeded ticket that has a recorded answer, one
 * {@code recorded = true} result and a COMPLETED ticket. It writes nothing else: no outbox event, job, attempt or
 * model invocation, because no live call happened in this workspace. It joins the caller's transaction (the
 * workspace lock of TicketService.reset), so a reset never leaves tickets without their results or the reverse.
 */
@Service
public class RecordedResultSeeder {
    private static final Logger log = LoggerFactory.getLogger(RecordedResultSeeder.class);
    private final RecordedTriageCatalog catalog;
    private final PackCatalog packs;
    private final TriageResultRepository results;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final PriorityScorer scorer = new PriorityScorer();

    RecordedResultSeeder(PackCatalog packs, TriageResultRepository results, ObjectMapper mapper, Clock clock) {
        this.packs = packs;
        this.catalog = RecordedTriageCatalog.load(packs, mapper);
        this.results = results;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Returns how many recorded results were inserted. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int seed(UUID workspaceId, String industry, List<Ticket> seededTickets) {
        PackRules pack = packs.require(industry);
        int inserted = 0;
        for (Ticket ticket : seededTickets) {
            Optional<RecordedTriage> recorded = ticket.getScenarioKey() == null ? Optional.empty()
                    : catalog.find(industry, ticket.getScenarioKey());
            if (recorded.isEmpty()) continue;
            if (!recorded.get().matches(ticket.getSubject(), ticket.getMessage())) {
                // The ticket text changed after the answer was captured: show no answer rather than a wrong one.
                log.warn("triage.recorded_result_skipped industry={} scenario={} reason=TICKET_TEXT_CHANGED", industry,
                        ticket.getScenarioKey());
                continue;
            }
            TriageDecision decision = recorded.get().toDecision(ticket.getId(), ticket.getSubject(), ticket.getMessage(),
                    pack, scorer);
            results.save(TriageResult.recorded(workspaceId, decision, recorded.get().capturedAt(), mapper));
            ticket.completed(clock.instant());
            inserted++;
        }
        return inserted;
    }
}
