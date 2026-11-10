package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harishdarko.caselens.demo.DemoTokenService;
import com.harishdarko.caselens.demo.DemoWorkspace;
import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketChannel;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The page polls the job while the worker runs. The claim must be committed before the AI call, so a poll can say
 * "the AI model is reading the ticket", and no database transaction may stay open while the provider answers.
 */
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.datasource.url=jdbc:h2:mem:triage-worker-transaction;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TriageWorkerTransactionTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DemoTokenService tokens;
    @Autowired TriageWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TriageJobRepository jobs;
    @Autowired TransactionTemplate transactions;
    @MockitoBean(name = "triageProvider") TriageProvider provider;

    @Test
    void aPollSeesProcessingWhileTheProviderIsWorkingAndNoTransactionIsOpenDuringTheCall() throws Exception {
        MockTriageProvider real = new MockTriageProvider();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean transactionOpenDuringCall = new AtomicBoolean(true);
        when(provider.providerName()).thenReturn(real.providerName());
        when(provider.modelVersion()).thenReturn(real.modelVersion());
        when(provider.promptVersion()).thenReturn(real.promptVersion());
        when(provider.analyze(any(), any())).thenAnswer(invocation -> {
            transactionOpenDuringCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return real.analyze(invocation.getArgument(0), invocation.getArgument(1));
        });
        String token = session();
        String ticketId = scenario(token);
        mvc.perform(post("/api/tickets/{id}/triage", ticketId).header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
        UUID eventId = UUID.fromString(processing(token, ticketId).get("job").get("eventId").asText());
        QueueMessage message = new QueueMessage(eventId, tokens.verify(token).workspaceId(), UUID.fromString(ticketId),
                "transaction-test", 1);

        CompletableFuture<WorkerResult> running = CompletableFuture.supplyAsync(() -> worker.process(message));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        String statusDuringCall = jdbc.queryForObject("select status from triage_job where event_id = ?", String.class, eventId);
        String pollDuringCall = processing(token, ticketId).get("job").get("status").asText();
        release.countDown();
        WorkerResult result = running.get(10, TimeUnit.SECONDS);

        assertThat(statusDuringCall).isEqualTo("PROCESSING");
        assertThat(pollDuringCall).isEqualTo("PROCESSING");
        assertThat(transactionOpenDuringCall).isFalse();
        assertThat(result.status()).isEqualTo(WorkerResultStatus.ACK);
        assertThat(processing(token, ticketId).get("job").get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void aClaimLeftByAWorkerThatDiedCanBeTakenAgainAfterTheLeaseButNotBefore() {
        UUID workspaceId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-10T12:00:00Z");
        OutboxEvent event = OutboxEvent.create(workspaceId, ticketId, "lease-test", now);
        transactions.executeWithoutResult(status -> {
            entityManager.persist(new DemoWorkspace(workspaceId, now, now.plusSeconds(86400)));
            entityManager.persist(Ticket.create(ticketId, workspaceId, "Payment issue",
                    "Payment was captured but the session never started for this ticket.", TicketChannel.WEB, null, now));
            entityManager.persist(event);
            entityManager.persist(TriageJob.queued(UUID.randomUUID(), event.getId(), workspaceId, ticketId, 1, now));
        });

        Integer first = transactions.execute(status -> jobs.claimForProcessing(event.getId(), now.plusSeconds(1)));
        Integer whileRunning = transactions.execute(status -> jobs.claimForProcessing(event.getId(), now.plusSeconds(60)));
        Integer afterLease = transactions.execute(status ->
                jobs.claimForProcessing(event.getId(), now.plusSeconds(1).plus(TriageJobRepository.CLAIM_LEASE).plusSeconds(1)));

        assertThat(first).isOne();
        assertThat(whileRunning).isZero();
        assertThat(afterLease).isOne();
        TriageJob job = jobs.findByEventId(event.getId()).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(TriageJobStatus.PROCESSING);
        assertThat(job.getAttemptCount()).isEqualTo(2);
    }

    private String session() throws Exception {
        String body = mvc.perform(post("/api/demo/session").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String scenario(String token) throws Exception {
        String ticket = mvc.perform(post("/api/demo/scenarios/charger-offline-site-wide").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(ticket).get("id").asText();
    }

    private JsonNode processing(String token, String ticketId) throws Exception {
        return objectMapper.readTree(mvc.perform(get("/api/tickets/{id}/processing", ticketId)
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString());
    }
}
