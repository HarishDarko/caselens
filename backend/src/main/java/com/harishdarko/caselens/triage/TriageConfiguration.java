package com.harishdarko.caselens.triage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harishdarko.caselens.ticket.TicketRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TriageConfiguration {
    @Bean
    @ConditionalOnMissingBean(OutboxPublisher.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "caselens.queue.enabled", havingValue = "false", matchIfMissing = true)
    OutboxPublisher outboxPublisher() {
        return new NoopOutboxPublisher();
    }

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
            @Value("${caselens.gemini.request-timeout:30s}") Duration requestTimeout,
            PolicyCatalog policyCatalog,
            ObjectMapper objectMapper) {
        if ("mock".equalsIgnoreCase(provider)) return new MockTriageProvider();
        if ("gemini".equalsIgnoreCase(provider)) {
            HttpClient client = geminiHttpClient(Duration.ofSeconds(2));
            return new GeminiTriageProvider(URI.create(endpoint), apiKey, model, client, objectMapper,
                    requestTimeout, () -> ThreadLocalRandom.current().nextInt(25, 126), policyCatalog);
        }
        throw new IllegalArgumentException("Unsupported AI provider");
    }

    static HttpClient geminiHttpClient(Duration connectTimeout) {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout).build();
    }

    @Bean
    TriageEngine triageEngine(@Qualifier("triageProvider") TriageProvider provider, RulesFallbackProvider fallback,
            PolicyCatalog policies, Clock clock, JpaModelInvocationRecorder invocationRecorder) {
        return new TriageEngine(new TicketRedactor(), provider, fallback, new SemanticValidator(policies),
                new PriorityScorer(), invocationRecorder, clock);
    }

    @Bean
    TriageWorker triageWorker(TriageJobRepository jobs, TicketRepository tickets, TriageResultRepository results,
            TriageAttemptRepository attempts, TriageEngine engine, ObjectMapper objectMapper, Clock clock,
            TriageMetrics metrics) {
        return new TriageWorker(jobs, tickets, results, attempts, engine, objectMapper, clock, metrics);
    }
}
