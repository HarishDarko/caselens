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
                context = new SpringApplicationBuilder(CaseLensApplication.class)
                        .web(WebApplicationType.SERVLET)
                        .properties(Map.of("server.port", "0", "server.address", "127.0.0.1",
                                "spring.profiles.active", "lambda"))
                        .run();
            }
            return context;
        }
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
