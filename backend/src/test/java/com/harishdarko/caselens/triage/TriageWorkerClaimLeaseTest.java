package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.harishdarko.caselens.demo.DemoWorkspace;
import com.harishdarko.caselens.ticket.Ticket;
import com.harishdarko.caselens.ticket.TicketChannel;
import com.harishdarko.caselens.ticket.TicketRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The worker commits its claim before the AI call, so the claim lease decides who may work on a job. These tests run
 * the real worker against the database with a fixed clock per worker: a delivery that meets a live claim is retried,
 * not dropped; a lapsed lease is taken again; a finished job is a duplicate; and a worker whose lease was taken over
 * while it waited for the provider does not overwrite the new owner's state.
 */
@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost/unused",
    "CASELENS_QUEUE_ENABLED=false",
    "spring.datasource.url=jdbc:h2:mem:triage-worker-claim-lease;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TriageWorkerClaimLeaseTest {
    private static final Instant T0 = Instant.parse("2026-11-10T12:00:00Z");

    @Autowired TriageJobRepository jobs;
    @Autowired TicketRepository tickets;
    @Autowired TriageResultRepository results;
    @Autowired TriageAttemptRepository attempts;
    @Autowired TriageEngine engine;
    @Autowired ObjectMapper mapper;
    @Autowired PackResolver packs;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(name = "triageProvider") TriageProvider provider;

    private final MockTriageProvider real = new MockTriageProvider();

    @BeforeEach
    void answerLikeTheMockProvider() {
        when(provider.providerName()).thenReturn(real.providerName());
        when(provider.modelVersion()).thenReturn(real.modelVersion());
        when(provider.promptVersion()).thenReturn(real.promptVersion());
        doAnswer(invocation -> real.analyze(invocation.getArgument(0), invocation.getArgument(1)))
                .when(provider).analyze(any(), any());
    }

    @Test
    void aDeliveryThatMeetsALiveClaimIsRetriedInsteadOfDropped() {
        QueueMessage message = seed();
        assertThat(claimAt(message, T0)).isOne(); // the first worker holds the job

        WorkerResult result = workerAt(T0.plusSeconds(30)).process(message);

        assertThat(result.status()).isEqualTo(WorkerResultStatus.RETRY);
        assertThat(result.errorCode()).isEqualTo("CLAIM_HELD");
        TriageJob job = jobs.findByEventId(message.eventId()).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(TriageJobStatus.PROCESSING);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(resultRows(message)).isZero();
    }

    @Test
    void aDeliveryAfterTheLeaseLapsedTakesTheJobAndCompletesIt() {
        QueueMessage message = seed();
        assertThat(claimAt(message, T0)).isOne(); // the first worker claimed it and then died

        WorkerResult result = workerAt(T0.plus(TriageJobRepository.CLAIM_LEASE).plusSeconds(1)).process(message);

        assertThat(result.status()).isEqualTo(WorkerResultStatus.ACK);
        TriageJob job = jobs.findByEventId(message.eventId()).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(TriageJobStatus.COMPLETED);
        assertThat(job.getAttemptCount()).isEqualTo(2);
        assertThat(resultRows(message)).isOne();
    }

    @Test
    void aDeliveryForACompletedJobIsADuplicate() {
        QueueMessage message = seed();
        assertThat(workerAt(T0).process(message).status()).isEqualTo(WorkerResultStatus.ACK);

        WorkerResult again = workerAt(T0.plusSeconds(5)).process(message);

        assertThat(again.status()).isEqualTo(WorkerResultStatus.ACK_DUPLICATE);
        assertThat(jobs.findByEventId(message.eventId()).orElseThrow().getAttemptCount()).isEqualTo(1);
        assertThat(resultRows(message)).isOne();
    }

    @Test
    void aWorkerWhoseLeaseWasTakenOverDropsItsFinalWrites() throws Exception {
        QueueMessage message = seed();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return real.analyze(invocation.getArgument(0), invocation.getArgument(1));
        }).when(provider).analyze(any(), any());

        CompletableFuture<WorkerResult> slow = CompletableFuture.supplyAsync(() -> workerAt(T0).process(message));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        Integer takenOver = claimAt(message, T0.plus(TriageJobRepository.CLAIM_LEASE).plusSeconds(1)); // a second worker
        release.countDown();
        WorkerResult result = slow.get(10, TimeUnit.SECONDS);

        assertThat(takenOver).isOne();
        assertThat(result.status()).isEqualTo(WorkerResultStatus.RETRY);
        assertThat(result.errorCode()).isEqualTo("CLAIM_LOST");
        TriageJob job = jobs.findByEventId(message.eventId()).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(TriageJobStatus.PROCESSING);
        assertThat(job.getAttemptCount()).isEqualTo(2);
        assertThat(resultRows(message)).isZero();
        assertThat(jdbc.queryForObject("select status from tickets where id = ?", String.class, message.ticketId()))
                .isEqualTo("PROCESSING");
    }

    private QueueMessage seed() {
        UUID workspaceId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.create(workspaceId, ticketId, "lease-test", T0);
        transactions.executeWithoutResult(status -> {
            entityManager.persist(new DemoWorkspace(workspaceId, T0, T0.plusSeconds(86400)));
            entityManager.persist(Ticket.create(ticketId, workspaceId, "Payment issue",
                    "Payment was captured but the session never started for this ticket.", TicketChannel.WEB, null, T0));
            entityManager.persist(event);
            entityManager.persist(TriageJob.queued(UUID.randomUUID(), event.getId(), workspaceId, ticketId, 1, T0));
        });
        return new QueueMessage(event.getId(), workspaceId, ticketId, "lease-test", 1);
    }

    private Integer claimAt(QueueMessage message, Instant now) {
        return transactions.execute(status -> jobs.claimForProcessing(message.eventId(), now));
    }

    private int resultRows(QueueMessage message) {
        return jdbc.queryForObject("select count(*) from triage_result where event_id = ?", Integer.class, message.eventId());
    }

    private TriageWorker workerAt(Instant now) {
        return new TriageWorker(jobs, tickets, results, attempts, engine, mapper, Clock.fixed(now, ZoneOffset.UTC),
                TriageMetrics.noop(), new DemoFailurePolicy(false), packs, transactions);
    }
}
