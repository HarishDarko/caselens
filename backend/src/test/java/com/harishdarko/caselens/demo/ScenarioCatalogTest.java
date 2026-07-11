package com.harishdarko.caselens.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScenarioCatalogTest {
    @Test
    void loadsTheEightVersionedSyntheticEvScenarios() {
        ScenarioCatalog catalog = ScenarioCatalog.fromClasspath("demo/scenarios-v1.json");

        assertThat(catalog.all()).hasSize(8);
        DemoScenario scenario = catalog.require("payment-captured-session-not-started");
        assertThat(scenario.subject()).isEqualTo("Payment accepted but charging did not start");
        assertThat(scenario.message()).contains("CHG-CA-1042", "SESSION-DEMO-88421");
        assertThat(scenario.message()).doesNotContainIgnoringCase("Blink");
    }
}
