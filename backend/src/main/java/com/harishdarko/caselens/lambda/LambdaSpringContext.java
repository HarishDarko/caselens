package com.harishdarko.caselens.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harishdarko.caselens.CaseLensApplication;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

final class LambdaSpringContext {
    private static volatile ConfigurableApplicationContext context;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private LambdaSpringContext() {}

    static ConfigurableApplicationContext get() {
        ConfigurableApplicationContext current = context;
        if (current != null) return current;
        synchronized (LambdaSpringContext.class) {
            if (context == null) {
                ObjectMapper mapper = new ObjectMapper();
                LambdaSecrets.loadIfConfigured(mapper);
                context = applicationBuilder().run();
            }
            return context;
        }
    }

    static SpringApplicationBuilder applicationBuilder() {
        System.setProperty("spring.profiles.active", "lambda");
        System.setProperty("spring.datasource.hikari.minimum-idle", "0");
        System.setProperty("spring.datasource.hikari.maximum-pool-size", "4");
        System.setProperty("spring.datasource.hikari.allow-pool-suspension", "true");
        System.setProperty("spring.datasource.hikari.connection-timeout", "3000");
        System.setProperty("spring.datasource.hikari.validation-timeout", "1000");
        return new SpringApplicationBuilder(CaseLensApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("lambda")
                .properties(Map.of("server.port", "0", "server.address", "127.0.0.1"));
    }

    static ObjectMapper mapper() {
        return get().getBean(ObjectMapper.class);
    }

    static HttpClient httpClient() {
        return HTTP_CLIENT;
    }

    static String baseUrl() {
        ServletWebServerApplicationContext web = (ServletWebServerApplicationContext) get();
        return "http://127.0.0.1:" + web.getWebServer().getPort();
    }
}
