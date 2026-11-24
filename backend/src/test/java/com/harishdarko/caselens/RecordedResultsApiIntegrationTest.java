package com.harishdarko.caselens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A seeded workspace opens with three recorded results per industry (real captured model answers) and five
 * untriaged tickets. The ticket list and the seeded session carry each ticket's latest result and latest review; a
 * live run on a recorded ticket queues a real job, and its result becomes the latest; and recorded results never
 * reach the live figures (latency, provider usage, fallbacks, queue counts, recent activity).
 */
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.datasource.url=jdbc:h2:mem:recorded-results-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecordedResultsApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DemoTokenService tokens;
    @Autowired TriageResultRepository results;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theSeededSessionCarriesThreeRecordedResultsAndFiveUntriagedTicketsPerIndustry() throws Exception {
        for (String industry : List.of("ev-charging", "saas-support", "e-commerce")) {
            JsonNode session = openSeeded(industry);
            JsonNode tickets = session.get("tickets");

            assertThat(tickets).as(industry).hasSize(8);
            List<JsonNode> recorded = new ArrayList<>();
            for (JsonNode ticket : tickets) {
                assertThat(ticket.has("latestFeedback")).as(industry).isTrue();
                assertThat(ticket.get("latestFeedback").isNull()).isTrue();
                if (ticket.get("latestResult").isNull()) {
                    assertThat(ticket.get("status").asText()).isEqualTo("NEW");
                } else {
                    recorded.add(ticket);
                }
            }
            assertThat(recorded).as(industry).hasSize(3);
            for (JsonNode ticket : recorded) {
                JsonNode result = ticket.get("latestResult");
                assertThat(ticket.get("status").asText()).isEqualTo("COMPLETED");
                assertThat(result.get("recorded").asBoolean()).isTrue();
                assertThat(result.get("ticketId").asText()).isEqualTo(ticket.get("id").asText());
                assertThat(result.get("decisionSource").asText()).isEqualTo("AI_VALIDATED");
                assertThat(result.get("modelVersion").asText()).isEqualTo("openai/gpt-oss-20b");
                assertThat(result.get("promptVersion").asText()).isEqualTo("triage-v2");
                assertThat(result.get("fallbackReason").isNull()).isTrue();
                assertThat(result.get("categoryLabel").asText()).isNotBlank();
                assertThat(result.get("categoryDescription").asText()).isNotBlank();
                assertThat(result.get("policyVersion").asText()).isNotBlank();
                assertThat(result.get("policies").size()).isEqualTo(result.get("policyIds").size());
                assertThat(result.get("priorityScore").asInt()).isBetween(0, 100);
                assertThat(result.get("appliedRules")).isNotEmpty();
                assertThat(result.get("evidence")).isNotEmpty();
                assertThat(result.get("createdAt").asText()).startsWith("2026-11-24T");
            }
        }
    }

    @Test
    void theSeededSessionAndTheTicketListAnswerTheSameRecordedResults() throws Exception {
        JsonNode session = openSeeded("saas-support");
        String token = session.get("token").asText();

        JsonNode listed = json(mvc.perform(get("/api/tickets?size=50").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content");

        assertThat(listed).hasSize(8);
        for (JsonNode ticket : session.get("tickets")) {
            JsonNode same = null;
            for (JsonNode candidate : listed) if (candidate.get("id").equals(ticket.get("id"))) same = candidate;
            assertThat(same).isNotNull();
            assertThat(same.get("latestResult").get("id")).isEqualTo(ticket.get("latestResult").get("id"));
        }
        JsonNode single = json(mvc.perform(get("/api/tickets/{id}", recordedTicket(session).get("id").asText())
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(single.get("latestResult").get("recorded").asBoolean()).isTrue();
    }

    @Test
    void seedingWritesNoOutboxEventJobAttemptOrModelCall() throws Exception {
        JsonNode session = openSeeded("e-commerce");
        UUID workspace = tokens.verify(session.get("token").asText()).workspaceId();

        assertThat(count("select count(*) from triage_result where workspace_id = ? and recorded = true", workspace)).isEqualTo(3);
        assertThat(count("select count(*) from triage_result where workspace_id = ? and event_id is not null", workspace)).isZero();
        assertThat(count("select count(*) from outbox_event where workspace_id = ?", workspace)).isZero();
        assertThat(count("select count(*) from triage_job where workspace_id = ?", workspace)).isZero();
        assertThat(count("select count(*) from model_invocations m join tickets t on t.id = m.ticket_id where t.workspace_id = ?",
                workspace)).isZero();
    }

    @Test
    void resettingAgainShowsTheThreeRecordedResultsOnTheNewTickets() throws Exception {
        JsonNode session = openSeeded("ev-charging");
        String token = session.get("token").asText();

        mvc.perform(post("/api/demo/reset").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        JsonNode listed = json(mvc.perform(get("/api/tickets?size=50").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content");
        assertThat(listed).hasSize(8);
        assertThat(listed.findValues("latestResult").stream().filter(node -> !node.isNull())).hasSize(3);
    }

    @Test
    void anAcceptedAndACorrectedRecordedResultShowTheirLatestReviewOnTheList() throws Exception {
        JsonNode session = openSeeded("saas-support");
        String token = session.get("token").asText();
        List<JsonNode> recorded = recordedTickets(session);
        JsonNode accepted = recorded.get(0);
        JsonNode corrected = recorded.get(1);

        review(token, accepted, "{\"decision\":\"ACCEPTED\"}");
        String urgency = corrected.get("latestResult").get("urgency").asText().equals("LOW") ? "HIGH" : "LOW";
        review(token, corrected, "{\"decision\":\"CORRECTED\",\"urgency\":\"" + urgency + "\",\"note\":\"Check the SLA\"}");

        JsonNode listed = json(mvc.perform(get("/api/tickets?size=50").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content");
        JsonNode acceptedFeedback = byId(listed, accepted).get("latestFeedback");
        assertThat(acceptedFeedback.get("decision").asText()).isEqualTo("ACCEPTED");
        assertThat(acceptedFeedback.get("urgency").isNull()).isTrue();
        assertThat(acceptedFeedback.get("note").isNull()).isTrue();
        assertThat(acceptedFeedback.get("createdAt").asText()).isNotBlank();
        JsonNode correctedFeedback = byId(listed, corrected).get("latestFeedback");
        assertThat(correctedFeedback.get("decision").asText()).isEqualTo("CORRECTED");
        assertThat(correctedFeedback.get("urgency").asText()).isEqualTo(urgency);
        assertThat(correctedFeedback.get("note").asText()).isEqualTo("Check the SLA");
        assertThat(byId(listed, recorded.get(2)).get("latestFeedback").isNull()).isTrue();
    }

    @Test
    void reRunningLiveOnARecordedTicketQueuesARealJobAndTheNewResultBecomesTheLatest() throws Exception {
        JsonNode session = openSeeded("ev-charging");
        String token = session.get("token").asText();
        UUID workspace = tokens.verify(token).workspaceId();
        JsonNode ticket = recordedTicket(session);
        String ticketId = ticket.get("id").asText();
        review(token, ticket, "{\"decision\":\"ACCEPTED\"}");

        mvc.perform(post("/api/tickets/{id}/triage", ticketId).header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());

        JsonNode processing = json(mvc.perform(get("/api/tickets/{id}/processing", ticketId)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(processing.get("job").isNull()).isFalse();
        assertThat(processing.get("job").get("status").asText()).isEqualTo("QUEUED");
        assertThat(count("select count(*) from triage_job where workspace_id = ?", workspace)).isEqualTo(1);
        assertThat(count("select count(*) from outbox_event where workspace_id = ?", workspace)).isEqualTo(1);

        UUID eventId = UUID.fromString(processing.get("job").get("eventId").asText());
        results.save(TriageResult.from(eventId, workspace, liveDecision(ticketId), Instant.now(), objectMapper));

        JsonNode listed = json(mvc.perform(get("/api/tickets?size=50").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content");
        JsonNode latest = byId(listed, ticket);
        assertThat(latest.get("latestResult").get("recorded").asBoolean()).isFalse();
        assertThat(latest.get("latestResult").get("modelVersion").asText()).isEqualTo("live-model");
        // The review was of the recorded result; the new live result starts unreviewed.
        assertThat(latest.get("latestFeedback").isNull()).isTrue();
    }

    @Test
    void aFreshlySeededWorkspaceHasNoLiveFiguresAndNullInsteadOfZeroRates() throws Exception {
        JsonNode session = openSeeded("saas-support");
        String token = session.get("token").asText();

        JsonNode evaluation = json(mvc.perform(get("/api/evaluation").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(evaluation.get("recordedResults").asInt()).isEqualTo(3);
        assertThat(evaluation.get("triageResults").asInt()).isZero();
        assertThat(evaluation.get("medianLatencyMs").isNull()).isTrue();
        assertThat(evaluation.get("p95LatencyMs").isNull()).isTrue();
        assertThat(evaluation.get("failureRate").isNull()).isTrue();
        assertThat(evaluation.get("providerUsage")).isEmpty();
        assertThat(evaluation.get("recentEvents")).isEmpty();
        assertThat(evaluation.get("reviewedResults").asInt()).isZero();
        assertThat(evaluation.get("acceptedResults").asInt()).isZero();
        assertThat(evaluation.get("acceptanceRate").isNull()).isTrue();
        assertThat(evaluation.get("overrideRate").isNull()).isTrue();
        assertThat(evaluation.get("correctionRate").asDouble()).isZero();

        JsonNode operations = json(mvc.perform(get("/api/operations/overview").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(operations.get("queue").get("completed").asInt()).isZero();
        assertThat(operations.get("fallbackCount").asInt()).isZero();
        assertThat(operations.get("providerFailureCount").asInt()).isZero();
        assertThat(operations.get("recent")).isEmpty();
        assertThat(operations.get("providers")).isEmpty();
    }

    @Test
    void reviewingRecordedResultsFillsTheAcceptanceAndOverrideRatesBesideTheBenchmark() throws Exception {
        JsonNode session = openSeeded("e-commerce");
        String token = session.get("token").asText();
        List<JsonNode> recorded = recordedTickets(session);
        review(token, recorded.get(0), "{\"decision\":\"ACCEPTED\"}");
        review(token, recorded.get(1), "{\"decision\":\"ACCEPTED\"}");
        String urgency = recorded.get(2).get("latestResult").get("urgency").asText().equals("LOW") ? "HIGH" : "LOW";
        review(token, recorded.get(2), "{\"decision\":\"CORRECTED\",\"urgency\":\"" + urgency + "\"}");

        JsonNode evaluation = json(mvc.perform(get("/api/evaluation").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(evaluation.get("reviewedResults").asInt()).isEqualTo(3);
        assertThat(evaluation.get("acceptedResults").asInt()).isEqualTo(2);
        assertThat(evaluation.get("acceptanceRate").asDouble()).isEqualTo(2.0 / 3.0);
        assertThat(evaluation.get("overrideRate").asDouble()).isEqualTo(1.0 / 3.0);
        assertThat(evaluation.get("recordedResults").asInt()).isEqualTo(3);
        // Still no live measurement.
        assertThat(evaluation.get("medianLatencyMs").isNull()).isTrue();
        assertThat(evaluation.get("triageResults").asInt()).isZero();
    }

    private TriageDecision liveDecision(String ticketId) {
        return new TriageDecision(UUID.fromString(ticketId), Category.of("GENERAL"), Urgency.LOW, SlaRisk.LOW,
                Sentiment.NEUTRAL, "A live answer to the re-run ticket.", List.of(), List.of(),
                "A live explanation that is long enough to be valid.", List.of("Reply to the customer"),
                "Thank you for your message, we are looking into it.", ReliabilitySignal.MEDIUM, List.of(), 10, List.of(),
                DecisionSource.AI_VALIDATED, "live-model", "triage-v2");
    }

    private void review(String token, JsonNode ticket, String body) throws Exception {
        String withResult = body.substring(0, body.length() - 1) + ",\"originalResultId\":\""
                + ticket.get("latestResult").get("id").asText() + "\"}";
        mvc.perform(post("/api/tickets/{id}/feedback", ticket.get("id").asText()).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(withResult)).andExpect(status().isCreated());
    }

    private JsonNode byId(JsonNode tickets, JsonNode ticket) {
        for (JsonNode candidate : tickets) if (candidate.get("id").equals(ticket.get("id"))) return candidate;
        throw new AssertionError("Ticket not listed: " + ticket.get("id"));
    }

    private JsonNode recordedTicket(JsonNode session) {
        return recordedTickets(session).get(0);
    }

    private List<JsonNode> recordedTickets(JsonNode session) {
        List<JsonNode> recorded = new ArrayList<>();
        for (JsonNode ticket : session.get("tickets")) if (!ticket.get("latestResult").isNull()) recorded.add(ticket);
        return recorded;
    }

    private long count(String sql, UUID workspace) {
        return jdbc.queryForObject(sql, Long.class, workspace);
    }

    private JsonNode openSeeded(String industry) throws Exception {
        return json(mvc.perform(post("/api/demo/session?seed=true").contentType(MediaType.APPLICATION_JSON)
                .content("{\"industry\":\"" + industry + "\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode json(String body) {
        return objectMapper.readTree(body);
    }
}
