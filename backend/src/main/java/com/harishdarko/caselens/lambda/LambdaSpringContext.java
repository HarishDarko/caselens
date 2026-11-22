package com.harishdarko.caselens.lambda;

import com.harishdarko.caselens.CaseLensApplication;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.ObjectMapper;

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
        // Same as application-lambda.yml: room for a Neon resume, well under the 30 s function timeout.
        System.setProperty("spring.datasource.hikari.connection-timeout", "10000");
        System.setProperty("spring.datasource.hikari.validation-timeout", "3000");
        return new SpringApplicationBuilder(CaseLensApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("lambda")
                .properties(Map.of("server.port", "0", "server.address", "127.0.0.1"));
    }

    static ObjectMapper mapper() {
        return get().getBean(ObjectMapper.class);
    }

    /** The origins Spring's CORS configuration allows (caselens.web.origins, set by Terraform). */
    static java.util.Set<String> webOrigins() {
        String origins = get().getEnvironment().getProperty("caselens.web.origins", "");
        return java.util.Arrays.stream(origins.split(",")).map(String::trim).filter(origin -> !origin.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static HttpClient httpClient() {
        return HTTP_CLIENT;
    }

    static String baseUrl() {
        ServletWebServerApplicationContext web = (ServletWebServerApplicationContext) get();
        return "http://127.0.0.1:" + web.getWebServer().getPort();
    }
}
