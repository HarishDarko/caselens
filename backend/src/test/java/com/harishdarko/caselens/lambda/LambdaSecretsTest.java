package com.harishdarko.caselens.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

class LambdaSecretsTest {
    private static final String[] SYSTEM_KEYS = {
            "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
            "CASELENS_DEMO_PASSCODE", "CASELENS_SESSION_SECRET", "CASELENS_AI_PROVIDER", "GROQ_API_KEY",
            "spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
            "caselens.demo-passcode", "caselens.session-secret", "caselens.ai-provider", "caselens.groq.api-key"
    };

    @AfterEach
    void clearProperties() {
        for (String key : SYSTEM_KEYS) System.clearProperty(key);
    }

    @Test
    void loadsOnlyAllowlistedRuntimeConfigurationWithoutLoggingOrReturningValues() {
        SecretsManagerClient client = mock(SecretsManagerClient.class);
        when(client.getSecretValue(GetSecretValueRequest.builder().secretId("caselens-demo/application").build()))
                .thenReturn(GetSecretValueResponse.builder().secretString("""
                        {"SPRING_DATASOURCE_URL":"jdbc:postgresql://neon.example/caselens",
                         "SPRING_DATASOURCE_USERNAME":"case_user",
                         "SPRING_DATASOURCE_PASSWORD":"secret-password",
                         "CASELENS_DEMO_PASSCODE":"reviewer",
                         "CASELENS_SESSION_SECRET":"session-secret",
                         "CASELENS_AI_PROVIDER":"groq",
                         "GROQ_API_KEY":"provider-secret",
                         "RAW_PROVIDER_RESPONSE":"must-not-load"}
                        """).build());

        LambdaSecrets.load(client, new ObjectMapper(), "caselens-demo/application");

        assertThat(System.getProperty("SPRING_DATASOURCE_URL")).startsWith("jdbc:postgresql://neon.example");
        assertThat(System.getProperty("spring.datasource.url")).startsWith("jdbc:postgresql://neon.example");
        assertThat(System.getProperty("spring.datasource.username")).isEqualTo("case_user");
        assertThat(System.getProperty("CASELENS_AI_PROVIDER")).isEqualTo("groq");
        assertThat(System.getProperty("caselens.ai-provider")).isEqualTo("groq");
        assertThat(System.getProperty("caselens.groq.api-key")).isEqualTo("provider-secret");
        assertThat(System.getProperty("RAW_PROVIDER_RESPONSE")).isNull();
        assertThat(LambdaSecrets.allowedKeys()).contains("GROQ_API_KEY");
    }
}
