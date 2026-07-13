package com.harishdarko.caselens.lambda;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class LambdaSnapStartConfigurationTest {
    @Test
    void lambdaProfileAllowsSpringBootToSuspendHikariAcrossSnapshots() throws Exception {
        var properties = new YamlPropertySourceLoader()
                .load("lambda", new ClassPathResource("application-lambda.yml"))
                .getFirst();

        org.assertj.core.api.Assertions.assertThat(
                        properties.getProperty("spring.datasource.hikari.allow-pool-suspension"))
                .isEqualTo(true);
    }

    @Test
    void lambdaBootstrapActivatesLambdaProfileAndCanonicalHikariProperties() {
        try {
            org.assertj.core.api.Assertions.assertThat(
                            LambdaSpringContext.applicationBuilder().application().getAdditionalProfiles())
                    .contains("lambda");
            org.assertj.core.api.Assertions.assertThat(System.getProperty("spring.profiles.active"))
                    .isEqualTo("lambda");
            org.assertj.core.api.Assertions.assertThat(
                            System.getProperty("spring.datasource.hikari.allow-pool-suspension"))
                    .isEqualTo("true");
        } finally {
            System.clearProperty("spring.profiles.active");
            System.clearProperty("spring.datasource.hikari.allow-pool-suspension");
            System.clearProperty("spring.datasource.hikari.minimum-idle");
            System.clearProperty("spring.datasource.hikari.maximum-pool-size");
            System.clearProperty("spring.datasource.hikari.connection-timeout");
            System.clearProperty("spring.datasource.hikari.validation-timeout");
        }
    }
}
