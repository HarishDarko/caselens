package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OutboxMigrationContractTest {
    @Test
    void migrationDeclaresIdentifierOnlyOutboxAndUniqueJobConstraints() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V3__create_outbox_and_triage_jobs.sql"));
        String repository = Files.readString(Path.of("src/main/java/com/harishdarko/caselens/triage/OutboxEventRepository.java"));

        assertThat(sql).contains("CREATE TABLE outbox_event", "CREATE TABLE triage_job", "CREATE TABLE triage_attempt",
                "event_id UUID NOT NULL UNIQUE")
                .contains("uq_triage_job_active_ticket_version")
                .doesNotContain("subject", "message", "raw_response");
        assertThat(repository).contains("FOR UPDATE SKIP LOCKED");
    }
}
