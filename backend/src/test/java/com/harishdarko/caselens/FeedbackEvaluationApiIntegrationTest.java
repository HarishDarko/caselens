package com.harishdarko.caselens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harishdarko.caselens.demo.DemoPrincipal;
import com.harishdarko.caselens.demo.DemoTokenService;
import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketRepository;
import com.harishdarko.caselens.triage.Category;
import com.harishdarko.caselens.triage.DecisionSource;
import com.harishdarko.caselens.triage.EvaluationGroundTruth;
import com.harishdarko.caselens.triage.EvaluationGroundTruthRepository;
import com.harishdarko.caselens.triage.TriageDecision;
import com.harishdarko.caselens.triage.TriageJob;
import com.harishdarko.caselens.triage.TriageJobRepository;
import com.harishdarko.caselens.triage.TriageResult;
import com.harishdarko.caselens.triage.TriageResultRepository;
import com.harishdarko.caselens.triage.ReliabilitySignal;
import com.harishdarko.caselens.triage.Sentiment;
import com.harishdarko.caselens.triage.SlaRisk;
import com.harishdarko.caselens.triage.Urgency;
import java.time.Instant;
import java.util.List;
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
    "spring.datasource.url=jdbc:h2:mem:feedback-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FeedbackEvaluationApiIntegrationTest {
    private static final Instant CREATED = Instant.parse("2026-07-11T15:00:00Z");
    private static final Instant RESULT_CREATED = CREATED.plusSeconds(2);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DemoTokenService tokens;
    @Autowired TicketRepository tickets;
    @Autowired TriageResultRepository results;
    @Autowired TriageJobRepository jobs;
    @Autowired EvaluationGroundTruthRepository groundTruth;

    @Test
    void acceptsScopedFeedbackAndReportsPersistedEvaluationMetrics() throws Exception {
        String token = session();
        DemoPrincipal principal = tokens.verify(token);
        String ticketId = createScenario(token);
        Ticket ticket = tickets.findById(UUID.fromString(ticketId)).orElseThrow();
        UUID eventId = UUID.randomUUID();
        TriageResult original = TriageResult.from(eventId, principal.workspaceId(), new TriageDecision(ticket.getId(),
                Category.CHARGING_SESSION, Urgency.MEDIUM, SlaRisk.MEDIUM, Sentiment.NEGATIVE,
                "Charging did not start", List.of(), List.of(), "The session did not start.",
                List.of("Inspect the charger"), "We are reviewing the session.", ReliabilitySignal.HIGH,
                List.of(), 55, List.of(), DecisionSource.AI_VALIDATED, "mock-v1", "triage-v1"), RESULT_CREATED, objectMapper);
        results.save(original);
        TriageJob job = TriageJob.queued(UUID.randomUUID(), eventId, principal.workspaceId(), ticket.getId(), 1, CREATED);
        job.claim(CREATED.plusSeconds(1));
        job.complete(CREATED.plusSeconds(2));
        jobs.save(job);
        groundTruth.save(new EvaluationGroundTruth("payment-captured-session-not-started", Category.CHARGING_SESSION, Urgency.HIGH));

        mvc.perform(post("/api/tickets/{id}/feedback", ticketId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"triageResultId\":\"" + original.getId()
                                + "\",\"urgency\":\"HIGH\",\"note\":\"Escalation confirmed\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalResultId").value(original.getId().toString()))
                .andExpect(jsonPath("$.correctedUrgency").value("HIGH"));

        mvc.perform(post("/api/tickets/{id}/feedback", ticketId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"triageResultId\":\"" + original.getId()
                                + "\",\"urgency\":\"MEDIUM\",\"note\":\"No actual change\"}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/tickets/{id}/feedback", ticketId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"triageResultId\":\"" + original.getId() + "\",\"urgency\":\"HIGH\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/tickets/{id}/feedback", ticketId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].correctedUrgency").value("HIGH"));

        mvc.perform(get("/api/evaluation").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triageResults").value(1))
                .andExpect(jsonPath("$.evaluatedResults").value(1))
                .andExpect(jsonPath("$.categoryAgreementRate").value(1.0))
                .andExpect(jsonPath("$.urgencyAgreementRate").value(0.0))
                .andExpect(jsonPath("$.correctionRate").value(1.0))
                .andExpect(jsonPath("$.medianLatencyMs").value(2000));

        assertThat(results.findById(original.getId()).orElseThrow().getUrgency()).isEqualTo(Urgency.MEDIUM);
    }

    @Test
    void rejectsFeedbackWithoutACompletedResultOrCorrection() throws Exception {
        String token = session();
        String ticketId = createScenario(token);

        mvc.perform(post("/api/tickets/{id}/feedback", ticketId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Nothing changed\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void doesNotAllowFeedbackAcrossDemoWorkspaces() throws Exception {
        String ownerToken = session();
        String otherToken = session();
        String ticketId = createScenario(ownerToken);

        mvc.perform(post("/api/tickets/{id}/feedback", ticketId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"urgency\":\"HIGH\",\"note\":\"Cross-workspace attempt\"}"))
                .andExpect(status().isNotFound());
    }

    private String session() throws Exception {
        String body = mvc.perform(post("/api/demo/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passcode\":\"reviewer\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String createScenario(String token) throws Exception {
        String body = mvc.perform(post("/api/demo/scenarios/payment-captured-session-not-started")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.get("id").asText();
    }
}
