package com.harishdarko.caselens.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RuntimeSummaryTest {
    @Test
    void returnsOnlyAllowlistedRuntimeLabels() {
        RuntimeSummary summary = RuntimeSummary.from(
                "local-review", "neon-postgresql", "localstack-sqs", "groq");

        assertThat(summary.environment()).isEqualTo(RuntimeSummary.Environment.LOCAL_REVIEW);
        assertThat(summary.database()).isEqualTo(RuntimeSummary.Database.NEON_POSTGRESQL);
        assertThat(summary.queue()).isEqualTo(RuntimeSummary.Queue.LOCALSTACK_SQS);
        assertThat(summary.aiProvider()).isEqualTo(RuntimeSummary.AiProvider.GROQ);
    }

    @Test
    void rejectsRawConfigurationValuesInsteadOfReflectingThem() {
        assertThatThrownByHostileValue("postgresql://user:secret@private.example/caselens");
        assertThatThrownByHostileValue("arn:aws:sqs:ca-central-1:123456789012:private-queue");
        assertThatThrownByHostileValue("gsk_secret-value");
    }

    private static void assertThatThrownByHostileValue(String value) {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> RuntimeSummary.from(value, value, value, value))
                .withMessageContaining("Unsupported runtime label");
    }
}
