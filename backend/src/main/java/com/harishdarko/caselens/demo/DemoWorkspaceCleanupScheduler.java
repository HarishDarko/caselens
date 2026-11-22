package com.harishdarko.caselens.demo;

import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The in-process cleanup timer for long-running servers (local review, Docker). It is off under the lambda profile:
 * Lambda freezes the JVM between invocations, so the timer would rarely fire, and the relay function's sweep does the
 * job there.
 */
@Component
@Profile("!lambda")
class DemoWorkspaceCleanupScheduler {
    private static final Duration BUDGET = Duration.ofSeconds(30);
    private final DemoRetentionSweep sweep;

    DemoWorkspaceCleanupScheduler(DemoRetentionSweep sweep) {
        this.sweep = sweep;
    }

    @Scheduled(initialDelayString = "${caselens.cleanup.initial-delay-ms:3600000}",
            fixedDelayString = "${caselens.cleanup.fixed-delay-ms:3600000}")
    void purgeExpiredWorkspaces() {
        sweep.purgeExpiredWorkspaces(BUDGET);
    }
}
