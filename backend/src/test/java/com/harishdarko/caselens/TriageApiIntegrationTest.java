package com.harishdarko.caselens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harishdarko.caselens.triage.OutboxEvent;
import com.harishdarko.caselens.triage.OutboxEventRepository;
import com.harishdarko.caselens.triage.TriageJobRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.datasource.url=jdbc:h2:mem:triage-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TriageApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TriageJobRepository jobs;
    @Autowired OutboxEventRepository events;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    @Test
    void createsOneProcessingJobAndReturnsItsStatusWithoutDuplicatingTheRequest() throws Exception {
        String token = session();
        String ticket = createTicket(token);

        String first = mvc.perform(post("/api/tickets/{id}/triage", ticket)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Correlation-ID", "corr-api-1"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-Correlation-ID", "corr-api-1"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String jobId = objectMapper.readTree(first).get("jobId").asText();

        mvc.perform(post("/api/tickets/{id}/triage", ticket)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId));

        String processing = mvc.perform(get("/api/tickets/{id}/processing", ticket)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        String eventId = objectMapper.readTree(processing).get("job").get("eventId").asText();
        assertThat(events.findById(UUID.fromString(eventId)).orElseThrow().getPublishedAt()).isNotNull();

        mvc.perform(get("/api/operations/failures")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void aRetryableFailureStaysWithSqsUntilRecoveryReplaysTheAcknowledgedOne() throws Exception {
        String token = session();
        String ticket = createTicket(token);
        String jobId = objectMapper.readTree(mvc.perform(post("/api/tickets/{id}/triage", ticket)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).get("jobId").asText();
        jdbc.update("update triage_job set status = 'RETRYABLE_FAILURE', attempt_count = 1, last_error_code = 'PROVIDER_TIMEOUT'"
                + " where id = ?", UUID.fromString(jobId));

        // Clicking the ticket again must not queue a second job while SQS still owns the first one's message.
        mvc.perform(post("/api/tickets/{id}/triage", ticket).header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.status").value("RETRYABLE_FAILURE"));
        mvc.perform(post("/api/operations/failures/{jobId}/retry", jobId).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        assertThat(jobCount(ticket)).isOne();

        // The synthetic demo failure was acknowledged, so reviewer recovery replays it as a new job.
        jdbc.update("update triage_job set last_error_code = 'SYNTHETIC_PROVIDER_TIMEOUT' where id = ?", UUID.fromString(jobId));
        mvc.perform(post("/api/operations/failures/{jobId}/retry", jobId).header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
        assertThat(jobCount(ticket)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from triage_job where replayed_from_job_id = ?", Integer.class,
                UUID.fromString(jobId))).isOne();
    }

    @Test
    void aRetryableFailureThatSqsGaveUpOnCanBeReplayed() throws Exception {
        String token = session();
        String ticket = createTicket(token);
        String jobId = objectMapper.readTree(mvc.perform(post("/api/tickets/{id}/triage", ticket)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).get("jobId").asText();
        // Last touched 11 minutes ago: past maxReceiveCount x visibility timeout, so the message is in the DLQ.
        jdbc.update("update triage_job set status = 'RETRYABLE_FAILURE', attempt_count = 5, last_error_code = 'PROVIDER_TIMEOUT',"
                + " created_at = ?, updated_at = ? where id = ?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(720)),
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(660)), UUID.fromString(jobId));

        mvc.perform(get("/api/operations/failures").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(jobId));
        mvc.perform(post("/api/operations/failures/{jobId}/retry", jobId).header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/operations/failures/{jobId}/retry", jobId).header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
        assertThat(jobCount(ticket)).as("a second click returns the one replacement").isEqualTo(2);
    }

    @Test
    void theRelayTriesFreshEventsFirstNeverDropsOneAndNeverRewritesAPublishedRow() {
        java.time.Instant base = java.time.Instant.parse("2020-01-01T00:00:00Z");
        OutboxEvent failing = events.save(OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "relay-failing", base));
        OutboxEvent retried = events.save(OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "relay-retried", base.plusSeconds(1)));
        OutboxEvent fresh = events.save(OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "relay-fresh", base.plusSeconds(2)));
        jdbc.update("update outbox_event set publish_attempts = 500 where id = ?", failing.getId());
        jdbc.update("update outbox_event set publish_attempts = 3 where id = ?", retried.getId());

        List<UUID> candidates = events.findPublishCandidates(100).stream().map(OutboxEvent::getId).toList();
        assertThat(candidates).as("an event is never abandoned").contains(failing.getId());
        assertThat(candidates.indexOf(fresh.getId())).isLessThan(candidates.indexOf(retried.getId()));
        assertThat(candidates.indexOf(retried.getId())).isLessThan(candidates.indexOf(failing.getId()));

        assertThat(lockInTransaction(fresh.getId())).isPresent();
        assertThat(events.markPublished(fresh.getId(), base.plusSeconds(10))).isOne();
        assertThat(events.markPublished(fresh.getId(), base.plusSeconds(20))).as("already published").isZero();
        assertThat(events.recordPublishFailure(fresh.getId(), "QUEUE_TRANSIENT")).as("published rows keep their state").isZero();
        assertThat(lockInTransaction(fresh.getId())).isEmpty();
        OutboxEvent stored = events.findById(fresh.getId()).orElseThrow();
        assertThat(stored.getPublishedAt()).isEqualTo(base.plusSeconds(10));
        assertThat(stored.getPublishAttempts()).isZero();
        assertThat(events.recordPublishFailure(retried.getId(), "QUEUE_TRANSIENT")).isOne();
        assertThat(events.findById(retried.getId()).orElseThrow().getPublishAttempts()).isEqualTo(4);
    }

    private java.util.Optional<OutboxEvent> lockInTransaction(UUID eventId) {
        return transactions.execute(status -> events.lockUnpublished(eventId));
    }

    private int jobCount(String ticket) {
        return jdbc.queryForObject("select count(*) from triage_job where ticket_id = ?", Integer.class, UUID.fromString(ticket));
    }

    private String session() throws Exception {
        String body = mvc.perform(post("/api/demo/session").contentType(MediaType.APPLICATION_JSON)
                        )
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String createTicket(String token) throws Exception {
        String body = mvc.perform(post("/api/tickets").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Payment issue\",\"message\":\"Payment was captured but the session never started for this ticket.\",\"channel\":\"WEB\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.get("id").asText();
    }
}
