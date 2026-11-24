package com.harishdarko.caselens.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** Pins the shipped AI call caps to what Groq's free tier can carry (see the comment in application.yml). */
class AiBudgetDefaultsTest {

    @Test
    void shippedDefaultsFitGroqFreeTier() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var props = yaml.getObject();

        assertThat(props.getProperty("caselens.ai.daily-call-budget")).isEqualTo("${CASELENS_AI_DAILY_CALL_BUDGET:80}");
        assertThat(props.getProperty("caselens.ai.daily-call-share-per-client")).isEqualTo("${CASELENS_AI_DAILY_CALL_SHARE_PER_CLIENT:20}");
    }
}
