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
    DemoScenarios demoScenarios(PackCatalog packs, DemoWorkspaceAccess workspaces) {
        return new DemoScenarios(DemoScenarios.fromClasspath(packs.ids()), workspaces);
    }
}
