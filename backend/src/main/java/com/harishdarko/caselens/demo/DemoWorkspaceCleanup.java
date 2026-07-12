package com.harishdarko.caselens.demo;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DemoWorkspaceCleanup {
    private static final Logger log = LoggerFactory.getLogger(DemoWorkspaceCleanup.class);
    private final DemoWorkspaceRepository workspaces;
    private final Clock clock;

    public DemoWorkspaceCleanup(DemoWorkspaceRepository workspaces, Clock clock) {
        this.workspaces = workspaces;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${caselens.cleanup.initial-delay-ms:3600000}",
            fixedDelayString = "${caselens.cleanup.fixed-delay-ms:3600000}")
    @Transactional
    public int deleteExpired() {
        int deleted = (int) workspaces.deleteByExpiresAtBefore(clock.instant());
        if (deleted > 0) log.info("demo.workspace_cleanup deleted={}", deleted);
        return deleted;
    }
}
