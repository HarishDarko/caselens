package com.harishdarko.caselens;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harishdarko.caselens.demo.DemoTokenService;
import com.harishdarko.caselens.triage.Category;
import com.harishdarko.caselens.triage.DecisionSource;
import com.harishdarko.caselens.triage.ReliabilitySignal;
import com.harishdarko.caselens.triage.Sentiment;
import com.harishdarko.caselens.triage.SlaRisk;
import com.harishdarko.caselens.triage.TriageDecision;
import com.harishdarko.caselens.triage.TriageJobRepository;
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

/**
 * The result the ticket detail shows: the stored result plus what the page needs to explain it without its own copy
 * of the packs (category label and description, policy version, each policy's label and guidance), whether it is a
 * recorded result, and, for a ticket that has only a recorded result, no job.
 */
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.datasource.url=jdbc:h2:mem:result-view-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ResultViewApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DemoTokenService tokens;
    @Autowired TriageResultRepository results;
    @Autowired TriageJobRepository jobs;

    @Test
    void aLiveResultCarriesItsPackLabelsAndPolicyGuidance() throws Exception {
        String token = session(null);
        String ticket = scenario(token, "payment-captured-session-not-started");
        mvc.perform(post("/api/tickets/{id}/triage", ticket).header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
        UUID eventId = UUID.fromString(objectMapper.readTree(processing(token, ticket)).get("job").get("eventId").asText());
        results.save(TriageResult.from(eventId, workspace(token), decision(ticket, "CHARGING_SESSION",
                List.of("PAYMENT_CAPTURE_NO_SESSION")), Instant.now(), objectMapper));

        mvc.perform(get("/api/tickets/{id}/processing", ticket).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.result.recorded").value(false))
                .andExpect(jsonPath("$.result.category").value("CHARGING_SESSION"))
                .andExpect(jsonPath("$.result.categoryLabel").value("Charging session"))
                .andExpect(jsonPath("$.result.categoryDescription")
                        .value("A charging session that did not start, stopped early or charged slowly."))
                .andExpect(jsonPath("$.result.policyVersion").value("v1"))
                .andExpect(jsonPath("$.result.policyIds[0]").value("PAYMENT_CAPTURE_NO_SESSION"))
                .andExpect(jsonPath("$.result.policies.length()").value(1))
                .andExpect(jsonPath("$.result.policies[0].id").value("PAYMENT_CAPTURE_NO_SESSION"))
                .andExpect(jsonPath("$.result.policies[0].label").value("Payment captured, no session"))
                .andExpect(jsonPath("$.result.policies[0].guidance")
                        .value("Verify session and payment state; do not promise a refund before confirmation."))
                .andExpect(jsonPath("$.result.fallbackReason").isEmpty());
    }

    @Test
    void labelsComeFromTheWorkspacesOwnIndustryPack() throws Exception {
        String token = session("saas-support");
        String ticket = scenario(token, "saas-sso-lockout");
        results.save(TriageResult.recorded(workspace(token), decision(ticket, "LOGIN_ACCESS", List.of("SSO_LOCKOUT")),
                Instant.now(), objectMapper));

        mvc.perform(get("/api/tickets/{id}/processing", ticket).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.categoryLabel").value("Login and access"))
                .andExpect(jsonPath("$.result.policyVersion").value("saas-v1"))
                .andExpect(jsonPath("$.result.policies[0].label").value("SSO lockout"));
    }

    @Test
    void aTicketWithOnlyARecordedResultAnswersWithNoJobAndTheResult() throws Exception {
        String token = session(null);
        String ticket = scenario(token, "charger-offline-site-wide");
        TriageResult recorded = results.save(TriageResult.recorded(workspace(token),
                decision(ticket, "CHARGER_HARDWARE", List.of()), Instant.now(), objectMapper));

        mvc.perform(get("/api/tickets/{id}/processing", ticket).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job").isEmpty())
                .andExpect(jsonPath("$.result.id").value(recorded.getId().toString()))
                .andExpect(jsonPath("$.result.recorded").value(true))
                .andExpect(jsonPath("$.result.policies.length()").value(0));
    }

    @Test
    void aTicketWithNeitherAJobNorAResultIsStillNotFound() throws Exception {
        String token = session(null);
        String ticket = scenario(token, "connector-fault-single-port");

        mvc.perform(get("/api/tickets/{id}/processing", ticket).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherWorkspacesRecordedResultIsNotVisible() throws Exception {
        String owner = session(null);
        String ticket = scenario(owner, "receipt-request-general");
        results.save(TriageResult.recorded(workspace(owner), decision(ticket, "BILLING", List.of()), Instant.now(), objectMapper));

        mvc.perform(get("/api/tickets/{id}/processing", ticket).header("Authorization", "Bearer " + session(null)))
                .andExpect(status().isNotFound());
    }

    private TriageDecision decision(String ticket, String category, List<String> policyIds) {
        return new TriageDecision(UUID.fromString(ticket), Category.of(category), Urgency.HIGH, SlaRisk.HIGH,
                Sentiment.NEGATIVE, "The customer reports a blocking problem.", List.of(), policyIds,
                "The ticket describes a blocking problem that needs review.", List.of("Check the account"),
                "We are looking into this now.", ReliabilitySignal.HIGH, List.of(), 80, List.of(),
                DecisionSource.AI_VALIDATED, "openai/gpt-oss-20b", "triage-v2");
    }

    private UUID workspace(String token) {
        return tokens.verify(token).workspaceId();
    }

    private String processing(String token, String ticket) throws Exception {
        return mvc.perform(get("/api/tickets/{id}/processing", ticket).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String session(String industry) throws Exception {
        String body = industry == null ? "{}" : "{\"industry\":\"" + industry + "\"}";
        return objectMapper.readTree(mvc.perform(post("/api/demo/session").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private String scenario(String token, String key) throws Exception {
        return objectMapper.readTree(mvc.perform(post("/api/demo/scenarios/{key}", key).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }
}
