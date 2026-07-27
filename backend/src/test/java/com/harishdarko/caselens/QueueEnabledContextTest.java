package com.harishdarko.caselens;

import static org.assertj.core.api.Assertions.assertThat;

import com.harishdarko.caselens.triage.SqsWorkerPoller;
import com.harishdarko.caselens.triage.TriageWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = {
    "CASELENS_SESSION_SECRET=test-session-secret-that-is-at-least-thirty-two-bytes",
    "CASELENS_TRIAGE_QUEUE_URL=http://localhost:4566/000000000000/caselens-triage",
    "CASELENS_QUEUE_ENABLED=true",
    "spring.profiles.active=local",
    "AWS_ENDPOINT_URL=http://localhost:4566",
    "spring.datasource.url=jdbc:h2:mem:queue-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "caselens.worker.poll-delay-ms=600000",
    "caselens.outbox.relay-delay-ms=600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QueueEnabledContextTest {
    @Autowired TriageWorker worker;
    @Autowired SqsWorkerPoller poller;

    @Test
    void wiresTheWorkerAndSqsPollerWhenQueueModeIsEnabled() {
        assertThat(worker).isNotNull();
        assertThat(poller).isNotNull();
    }
}
