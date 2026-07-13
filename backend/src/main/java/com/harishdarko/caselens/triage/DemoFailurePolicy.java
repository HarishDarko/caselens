package com.harishdarko.caselens.triage;

import java.util.UUID;

public final class DemoFailurePolicy {
    private static final String RETRY_SCENARIO = "provider-retry-demo";
    private final boolean enabled;

    public DemoFailurePolicy(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean shouldFail(String scenarioKey, int attemptNumber, UUID replayedFromJobId) {
        return enabled && RETRY_SCENARIO.equals(scenarioKey) && attemptNumber == 1 && replayedFromJobId == null;
    }
}
