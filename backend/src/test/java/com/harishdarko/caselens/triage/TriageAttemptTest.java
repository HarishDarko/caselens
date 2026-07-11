package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TriageAttemptTest {
    @Test
    void recordsACompletedAttemptWithoutExposingTicketContent() {
        Instant started = Instant.parse("2026-07-11T18:00:00Z");
        Instant finished = started.plusSeconds(2);
        TriageAttempt attempt = TriageAttempt.processing(UUID.randomUUID(), 1, started);
        attempt.complete(finished);

        assertThat(attempt.snapshot().status()).isEqualTo(TriageAttemptStatus.COMPLETED);
        assertThat(attempt.snapshot().completedAt()).isEqualTo(finished);
        assertThat(attempt.snapshot().errorCode()).isNull();
    }
}
