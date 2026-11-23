package com.harishdarko.caselens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * The review bar: Accept (decision ACCEPTED, no urgency or note needed) and Override (decision CORRECTED, the default
 * when a client sends no decision, with or without a note). Feedback stays append-only and requestId idempotent. The
 * redesigned frontend names the result originalResultId; older clients send triageResultId; both are accepted.
 */
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.datasource.url=jdbc:h2:mem:feedback-decision-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FeedbackDecisionApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DemoTokenService tokens;
    @Autowired TriageResultRepository results;

    @Test
    void acceptNeedsNoUrgencyOrNoteAndIsRecordedAsItsOwnEntry() throws Exception {
        Review review = review();

        review.submit("{\"decision\":\"ACCEPTED\",\"originalResultId\":\"" + review.resultId + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decision").value("ACCEPTED"))
                .andExpect(jsonPath("$.originalResultId").value(review.resultId.toString()))
                .andExpect(jsonPath("$.correctedUrgency").doesNotExist())
                .andExpect(jsonPath("$.note").doesNotExist());

        review.list()
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].decision").value("ACCEPTED"));
        assertThat(results.findById(review.resultId).orElseThrow().getUrgency()).isEqualTo(Urgency.MEDIUM);
    }

    @Test
    void anAcceptThatAlsoChangesAFieldIsAValidationError() throws Exception {
        Review review = review();

        review.submit("{\"decision\":\"ACCEPTED\",\"originalResultId\":\"" + review.resultId + "\",\"urgency\":\"HIGH\"}")
                .andExpect(status().isBadRequest());
        review.list().andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void aCorrectionIsTheDefaultDecisionAndNeedsNoNote() throws Exception {
        Review review = review();

        review.submit("{\"originalResultId\":\"" + review.resultId + "\",\"urgency\":\"HIGH\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decision").value("CORRECTED"))
                .andExpect(jsonPath("$.correctedUrgency").value("HIGH"))
                .andExpect(jsonPath("$.note").doesNotExist());
        review.submit("{\"decision\":\"CORRECTED\",\"triageResultId\":\"" + review.resultId
                        + "\",\"urgency\":\"CRITICAL\",\"note\":\"   \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.note").doesNotExist());
        review.submit("{\"decision\":\"CORRECTED\",\"originalResultId\":\"" + review.resultId
                        + "\",\"urgency\":\"LOW\",\"note\":\"Customer confirmed it is not blocking\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.note").value("Customer confirmed it is not blocking"));
    }

    @Test
    void aCorrectionThatChangesNothingIsStillAConflict() throws Exception {
        Review review = review();

        review.submit("{\"decision\":\"CORRECTED\",\"originalResultId\":\"" + review.resultId + "\",\"urgency\":\"MEDIUM\"}")
                .andExpect(status().isConflict());
        review.submit("{\"decision\":\"CORRECTED\",\"originalResultId\":\"" + review.resultId + "\"}")
                .andExpect(status().isConflict());
        review.list().andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void bothResultIdNamesAreAcceptedButMustAgree() throws Exception {
        Review review = review();

        review.submit("{\"decision\":\"ACCEPTED\",\"triageResultId\":\"" + review.resultId + "\",\"originalResultId\":\""
                        + review.resultId + "\"}")
                .andExpect(status().isCreated());
        review.submit("{\"decision\":\"ACCEPTED\",\"triageResultId\":\"" + review.resultId + "\",\"originalResultId\":\""
                        + UUID.randomUUID() + "\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void aResentAcceptIsOneEntryAndTheSameRequestIdWithAnotherDecisionIsAConflict() throws Exception {
        Review review = review();
        UUID requestId = UUID.randomUUID();
        String accept = "{\"decision\":\"ACCEPTED\",\"originalResultId\":\"" + review.resultId + "\",\"requestId\":\"" + requestId + "\"}";

        review.submit(accept).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(requestId.toString()));
        review.submit(accept).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(requestId.toString()));
        review.submit("{\"decision\":\"CORRECTED\",\"originalResultId\":\"" + review.resultId + "\",\"urgency\":\"HIGH\","
                        + "\"requestId\":\"" + requestId + "\"}")
                .andExpect(status().isConflict());

        review.list().andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void reviewIsAppendOnlySoAChangedReviewAddsAnEntryAndKeepsTheEarlierOnes() throws Exception {
        Review review = review();

        review.submit("{\"decision\":\"ACCEPTED\",\"originalResultId\":\"" + review.resultId + "\"}").andExpect(status().isCreated());
        review.submit("{\"decision\":\"CORRECTED\",\"originalResultId\":\"" + review.resultId + "\",\"urgency\":\"HIGH\"}")
                .andExpect(status().isCreated());
        review.submit("{\"decision\":\"ACCEPTED\",\"originalResultId\":\"" + review.resultId + "\"}").andExpect(status().isCreated());

        review.list()
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].decision").value("ACCEPTED"))
                .andExpect(jsonPath("$.content[1].decision").value("CORRECTED"))
                .andExpect(jsonPath("$.content[2].decision").value("ACCEPTED"));
        assertThat(results.findById(review.resultId).orElseThrow().getUrgency()).isEqualTo(Urgency.MEDIUM);
    }

    private Review review() throws Exception {
        String token = objectMapper.readTree(mvc.perform(post("/api/demo/session").contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        DemoPrincipal principal = tokens.verify(token);
        UUID ticketId = UUID.fromString(objectMapper.readTree(mvc.perform(
                        post("/api/demo/scenarios/payment-captured-session-not-started").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText());
        TriageResult result = results.save(TriageResult.recorded(principal.workspaceId(), new TriageDecision(ticketId,
                Category.of("CHARGING_SESSION"), Urgency.MEDIUM, SlaRisk.MEDIUM, Sentiment.NEGATIVE,
                "Charging did not start", List.of(), List.of(), "The session did not start.",
                List.of("Inspect the charger"), "We are reviewing the session.", ReliabilitySignal.HIGH,
                List.of(), 55, List.of(), DecisionSource.AI_VALIDATED, "mock-v1", "triage-v1"),
                Instant.parse("2026-07-11T15:00:02Z"), objectMapper));
        return new Review(token, ticketId, result.getId());
    }

    private final class Review {
        private final String token;
        private final UUID ticketId;
        private final UUID resultId;

        Review(String token, UUID ticketId, UUID resultId) {
            this.token = token;
            this.ticketId = ticketId;
            this.resultId = resultId;
        }

        ResultActions submit(String body) throws Exception {
            return mvc.perform(post("/api/tickets/{id}/feedback", ticketId).header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(body));
        }

        ResultActions list() throws Exception {
            return mvc.perform(get("/api/tickets/{id}/feedback", ticketId).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }
}
