package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TriageConfigurationTest {
    @Test
    void buildsGeminiClientWithHttpOnePointOneForProviderCompatibility() {
        assertThat(TriageConfiguration.geminiHttpClient(Duration.ofSeconds(2)).version())
                .isEqualTo(HttpClient.Version.HTTP_1_1);
    }
}
