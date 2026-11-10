package com.harishdarko.caselens.demo;

import com.harishdarko.caselens.triage.PackCatalog;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DemoConfiguration {
    @Bean Clock utcClock() { return Clock.systemUTC(); }

    @Bean
    DemoTokenService demoTokenService(@Value("${caselens.session-secret}") String secret, Clock clock) {
        return new DemoTokenService(secret, clock);
    }

    @Bean
    RuntimeSummary runtimeSummary(
            @Value("${caselens.runtime.environment:local-review}") String environment,
            @Value("${caselens.runtime.database:postgresql}") String database,
            @Value("${caselens.runtime.queue:localstack-sqs}") String queue,
            @Value("${caselens.ai-provider:mock}") String aiProvider) {
        return RuntimeSummary.from(environment, database, queue, aiProvider);
    }

    @Bean
    DemoScenarios demoScenarios(PackCatalog packs, DemoWorkspaceAccess workspaces) {
        return new DemoScenarios(DemoScenarios.fromClasspath(packs.ids()), workspaces);
    }
}
