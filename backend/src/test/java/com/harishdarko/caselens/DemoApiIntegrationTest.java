package com.harishdarko.caselens;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harishdarko.caselens.demo.DemoTokenService;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
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
    "spring.datasource.url=jdbc:h2:mem:api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DemoApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DemoTokenService tokens;
    @Autowired Clock clock;

    @Test
    void exchangesAValidPasscodeForASignedSession() throws Exception {
        mvc.perform(post("/api/demo/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passcode\":\"reviewer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void allowsTheConfiguredLocalReviewerOriginToCallTheApi() throws Exception {
        mvc.perform(options("/api/tickets")
                        .header("Origin", "http://localhost:4173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4173"));
    }

    @Test
    void keepsMetricsBehindDemoAuthentication() throws Exception {
        mvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnInvalidPasscodeWithoutLeakingDetails() throws Exception {
        mvc.perform(post("/api/demo/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passcode\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void validatesTicketFieldsAndCapsPageSize() throws Exception {
        String token = session();

        mvc.perform(post("/api/tickets")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"tiny\",\"message\":\"short\",\"channel\":\"WEB\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));

        mvc.perform(get("/api/tickets?size=51").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void isolatesTicketsAndResetOperationsByWorkspace() throws Exception {
        String tokenA = session();
        String tokenB = session();
        String ticketB = createTicket(tokenB, "Connector authorization rejected");

        mvc.perform(get("/api/tickets/{id}", ticketB).header("Authorization", bearer(tokenA)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/demo/reset").header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seeded").value(8));

        mvc.perform(get("/api/tickets/{id}", ticketB).header("Authorization", bearer(tokenB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Connector authorization rejected"));
    }

    @Test
    void loadsASpecificScenarioAndReturnsNewestTicketsFirst() throws Exception {
        String token = session();
        createTicket(token, "Older charging receipt request");

        mvc.perform(post("/api/demo/scenarios/payment-captured-session-not-started")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scenarioKey").value("payment-captured-session-not-started"))
                .andExpect(jsonPath("$.subject").value("Payment accepted but charging did not start"));

        mvc.perform(get("/api/tickets?page=0&size=20").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].scenarioKey")
                        .value("payment-captured-session-not-started"));
    }

    @Test
    void rejectsAValidlySignedTokenWhenItsWorkspaceDoesNotExist() throws Exception {
        String orphanedToken = tokens.create(UUID.randomUUID(), clock.instant().plus(Duration.ofHours(1)));

        mvc.perform(get("/api/tickets").header("Authorization", bearer(orphanedToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    private String session() throws Exception {
        String body = mvc.perform(post("/api/demo/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passcode\":\"reviewer\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String createTicket(String token, String subject) throws Exception {
        String body = mvc.perform(post("/api/tickets")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TicketInput(
                                subject,
                                "This is a synthetic ticket message long enough for validation.",
                                "WEB"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.get("id").asText();
    }

    private String bearer(String token) { return "Bearer " + token; }

    private record TicketInput(String subject, String message, String channel) {}
}
