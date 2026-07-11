package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PolicyCatalogTest {
    @Test
    void loadsVersionedSyntheticPoliciesAndExposesGuidance() {
        PolicyCatalog catalog = PolicyCatalog.load(new ByteArrayInputStream(("""
                version: v1
                policies:
                  - id: CONNECTOR_FAULT
                    guidance: Isolate the connector and check an alternate connector.
                """).getBytes(StandardCharsets.UTF_8)));

        assertThat(catalog.version()).isEqualTo("v1");
        assertThat(catalog.contains("CONNECTOR_FAULT")).isTrue();
        assertThat(catalog.guidance("CONNECTOR_FAULT"))
                .isEqualTo("Isolate the connector and check an alternate connector.");
    }
}
