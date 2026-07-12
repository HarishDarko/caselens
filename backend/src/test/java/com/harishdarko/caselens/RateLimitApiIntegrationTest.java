package com.harishdarko.caselens;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "CASELENS_DEMO_PASSCODE=reviewer",
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "CASELENS_RATE_LIMIT_SESSION_PER_MINUTE=2",
    "CASELENS_RATE_LIMIT_MUTATION_PER_MINUTE=1",
    "spring.datasource.url=jdbc:h2:mem:rate-limit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RateLimitApiIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void returnsRetryableProblemDetailsWhenTheSessionBudgetIsExceeded() throws Exception {
        String request = "{\"passcode\":\"reviewer\"}";
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/demo/session").contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isOk());
        }

        mvc.perform(post("/api/demo/session").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.title").value("Too many requests"));
    }

    @Test
    void usesTheForwardedClientAddressForGatewaySessionLimits() throws Exception {
        String request = "{\"passcode\":\"reviewer\"}";
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/demo/session")
                            .header("X-CaseLens-Client-Ip", "198.51.100.10")
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isOk());
        }

        mvc.perform(post("/api/demo/session")
                        .header("X-CaseLens-Client-Ip", "198.51.100.10")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isTooManyRequests());

        mvc.perform(post("/api/demo/session")
                        .header("X-CaseLens-Client-Ip", "198.51.100.11")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk());
    }

    @Test
    void exposesSafeLivenessAndReadinessProbes() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void limitsOtherMutatingDemoActionsByWorkspace() throws Exception {
        String body = mvc.perform(post("/api/demo/session")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"passcode\":\"reviewer\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("token").asText();

        mvc.perform(post("/api/demo/scenarios/charger-offline-site-wide")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/demo/scenarios/charger-offline-site-wide")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
    }
}
