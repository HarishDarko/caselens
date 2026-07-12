package com.harishdarko.caselens.lambda;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

final class LambdaSecrets {
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
            "CASELENS_DEMO_PASSCODE", "CASELENS_SESSION_SECRET", "CASELENS_AI_PROVIDER",
            "CASELENS_WEB_ORIGINS", "GEMINI_API_KEY", "GEMINI_MODEL", "GEMINI_ENDPOINT",
            "GROQ_API_KEY", "GROQ_MODEL", "GROQ_ENDPOINT");

    private LambdaSecrets() {}

    static Set<String> allowedKeys() {
        return ALLOWED_KEYS.stream().collect(Collectors.toUnmodifiableSet());
    }

    static void loadIfConfigured(ObjectMapper mapper) {
        String secretName = System.getenv("CASELENS_SECRETS_MANAGER_NAME");
        if (secretName == null || secretName.isBlank()) return;
        String region = System.getenv().getOrDefault("AWS_REGION", "ca-central-1");
        try (SecretsManagerClient client = SecretsManagerClient.builder().region(Region.of(region)).build()) {
            load(client, mapper, secretName);
        }
    }

    static void load(SecretsManagerClient client, ObjectMapper mapper, String secretName) {
        String secret = client.getSecretValue(GetSecretValueRequest.builder().secretId(secretName).build()).secretString();
        if (secret == null || secret.isBlank()) throw new IllegalStateException("Configured runtime secret is empty");
        try {
            Map<String, String> values = mapper.readValue(secret, new TypeReference<>() {});
            values.forEach((key, value) -> {
                if (ALLOWED_KEYS.contains(key) && value != null && !value.isBlank()) System.setProperty(key, value);
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Configured runtime secret must be a JSON object", exception);
        }
    }
}
