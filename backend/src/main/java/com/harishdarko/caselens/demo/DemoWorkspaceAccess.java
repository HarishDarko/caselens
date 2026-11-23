package com.harishdarko.caselens.demo;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoWorkspaceAccess {
    private final DemoWorkspaceRepository workspaces;
    private final Clock clock;

    DemoWorkspaceAccess(DemoWorkspaceRepository workspaces, Clock clock) {
        this.workspaces = workspaces;
        this.clock = clock;
    }

    /** The industry pack id of the workspace, or the default industry when the workspace no longer exists. */
    @Transactional(readOnly = true)
    public String industryOf(UUID workspaceId) {
        return workspaces.findById(workspaceId).map(DemoWorkspace::getIndustry).orElse(DemoWorkspace.DEFAULT_INDUSTRY);
    }

    /** The client key of the workspace that owns the ticket, when it has one; the AI share is counted against it. */
    @Transactional(readOnly = true)
    public Optional<String> clientKeyOfTicket(UUID ticketId) {
        return workspaces.findClientKeyByTicketId(ticketId);
    }

    /**
     * Holds the workspace row lock until the caller's transaction ends, so a per-workspace cap check and the insert
     * after it run one at a time for the workspace instead of each seeing the same count. When the row is gone (the
     * retention sweep deleted the expired workspace after the request's token check), the caller gets the same
     * "invalid or expired demo session" answer as at the door, instead of a foreign-key failure on the insert.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForUpdate(UUID workspaceId) {
        if (workspaces.findByIdForUpdate(workspaceId).isEmpty()) throw new InvalidDemoTokenException();
    }

    @Transactional(readOnly = true)
    public void requireActive(UUID workspaceId) {
        if (!workspaces.existsByIdAndExpiresAtAfter(workspaceId, clock.instant())) {
            throw new InvalidDemoTokenException();
        }
    }
}
