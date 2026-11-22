package com.harishdarko.caselens;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harishdarko.caselens.demo.DemoPrincipal;
import com.harishdarko.caselens.demo.DemoTokenService;
import com.harishdarko.caselens.triage.Category;
import com.harishdarko.caselens.triage.DecisionSource;
import com.harishdarko.caselens.triage.ReliabilitySignal;
import com.harishdarko.caselens.triage.Sentiment;
import com.harishdarko.caselens.triage.SlaRisk;
import com.harishdarko.caselens.triage.TriageDecision;
import com.harishdarko.caselens.triage.TriageResult;
import com.harishdarko.caselens.triage.TriageResultRepository;
import com.harishdarko.caselens.triage.Urgency;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "CASELENS_LIMIT_TICKETS_PER_WORKSPACE=2",
    "CASELENS_LIMIT_FEEDBACK_PER_WORKSPACE=1",
    "spring.datasource.url=jdbc:h2:mem:workspace-limits;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkspaceLimitsApiIntegrationTest {
    private static final String TICKET = "{\"subject\":\"Charger offline\","
            + "\"message\":\"The charger at bay four shows offline since noon.\",\"channel\":\"WEB\"}";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DemoTokenService tokens;
    @Autowired TriageResultRepository results;

    @Test
    void refusesTicketsPastTheWorkspaceCap() throws Exception {
        String token = session();
        createTicket(token);
        createTicket(token);

        mvc.perform(post("/api/tickets").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(TICKET))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Workspace limit reached"));
        mvc.perform(post("/api/demo/scenarios/charger-offline-site-wide").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Workspace limit reached"));

        // Another workspace has its own cap.
        createTicket(session());
    }

    @Test
    void refusesFeedbackPastTheWorkspaceCap() throws Exception {
        String token = session();
        DemoPrincipal principal = tokens.verify(token);
        UUID ticketId = UUID.fromString(createTicket(token));
        TriageResult original = TriageResult.from(UUID.randomUUID(), principal.workspaceId(), new TriageDecision(ticketId,
                Category.of("CHARGING_SESSION"), Urgency.MEDIUM, SlaRisk.MEDIUM, Sentiment.NEGATIVE,
                "Charger offline", List.of(), List.of(), "The charger is offline.",
                List.of("Inspect the charger"), "We are reviewing the charger.", ReliabilitySignal.HIGH,
                List.of(), 55, List.of(), DecisionSource.AI_VALIDATED, "mock-v1", "triage-v1"),
                Instant.parse("2026-07-11T15:00:00Z"), objectMapper);
        results.save(original);

        mvc.perform(feedback(token, ticketId, original.getId(), "HIGH")).andExpect(status().isCreated());
        mvc.perform(feedback(token, ticketId, original.getId(), "LOW"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Workspace limit reached"));
    }

    private org.springframework.test.web.servlet.RequestBuilder feedback(String token, UUID ticketId, UUID resultId,
            String urgency) {
        return post("/api/tickets/{id}/feedback", ticketId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"triageResultId\":\"" + resultId + "\",\"urgency\":\"" + urgency + "\",\"note\":\"Reviewed\"}");
    }

    private String createTicket(String token) throws Exception {
        String body = mvc.perform(post("/api/tickets").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(TICKET))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String session() throws Exception {
        String body = mvc.perform(post("/api/demo/session"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }
}
