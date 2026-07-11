package com.harishdarko.caselens.triage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TriageConfiguration {
    @Bean
    PolicyCatalog policyCatalog() {
        return PolicyCatalog.defaultCatalog();
    }

    @Bean
    RulesFallbackProvider rulesFallbackProvider() {
        return new RulesFallbackProvider();
    }

    @Bean
    TriageProvider triageProvider(
            @Value("${caselens.ai-provider:mock}") String provider,
            @Value("${caselens.gemini.endpoint:https://generativelanguage.googleapis.com/v1/interactions}") String endpoint,
            @Value("${caselens.gemini.api-key:}") String apiKey,
            @Value("${caselens.gemini.model:gemini-3.5-flash}") String model,
            ObjectMapper objectMapper) {
        if ("mock".equalsIgnoreCase(provider)) return new MockTriageProvider();
        if ("gemini".equalsIgnoreCase(provider)) {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            return new GeminiTriageProvider(URI.create(endpoint), apiKey, model, client, objectMapper,
                    Duration.ofSeconds(8), () -> ThreadLocalRandom.current().nextInt(25, 126));
        }
        throw new IllegalArgumentException("Unsupported AI provider");
    }

    @Bean
    TriageEngine triageEngine(@Qualifier("triageProvider") TriageProvider provider, RulesFallbackProvider fallback,
            PolicyCatalog policies, Clock clock, JpaModelInvocationRecorder invocationRecorder) {
        return new TriageEngine(new TicketRedactor(), provider, fallback, new SemanticValidator(policies),
                new PriorityScorer(), invocationRecorder, clock);
    }
}
