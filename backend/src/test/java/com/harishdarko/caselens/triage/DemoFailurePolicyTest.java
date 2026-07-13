package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DemoFailurePolicyTest {
    @Test
    void failsOnlyTheFirstAttemptOfTheOriginalFixedScenario() {
        DemoFailurePolicy policy = new DemoFailurePolicy(true);

        assertThat(policy.shouldFail("provider-retry-demo", 1, null)).isTrue();
        assertThat(policy.shouldFail("provider-retry-demo", 2, null)).isFalse();
        assertThat(policy.shouldFail("provider-retry-demo", 1, UUID.randomUUID())).isFalse();
        assertThat(policy.shouldFail("charger-offline-site-wide", 1, null)).isFalse();
    }

    @Test
    void remainsDisabledUnlessTheDemoConfigurationOptsIn() {
        assertThat(new DemoFailurePolicy(false).shouldFail("provider-retry-demo", 1, null)).isFalse();
    }
}
