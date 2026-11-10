package com.harishdarko.caselens.demo;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
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

    @Transactional(readOnly = true)
    public void requireActive(UUID workspaceId) {
        if (!workspaces.existsByIdAndExpiresAtAfter(workspaceId, clock.instant())) {
            throw new InvalidDemoTokenException();
        }
    }
}
