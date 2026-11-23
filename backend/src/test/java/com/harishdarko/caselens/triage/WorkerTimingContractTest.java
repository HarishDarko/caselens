package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The worker's time arithmetic against the deployed queue and Lambda settings in infra/terraform/main.tf. A Groq call,
 * its in-call rate-limit wait and retry included, must end inside the worker Lambda's timeout with room for the
 * claim before it, the rules fallback and the saves after it. The claim lease must outlive the Lambda, and the SQS
 * visibility timeout must outlive the lease, so a redelivery never meets a live claim of a worker that is still
 * running. The worker's last delivery must be SQS's last one, or a rate-limited job would go to the DLQ instead of
 * falling back to the rules.
 */
class WorkerTimingContractTest {
    private static final Duration SAVE_AND_FALLBACK_MARGIN = Duration.ofSeconds(10);
    private static final String TERRAFORM = readTerraform();

    @Test
    void aGroqCallFitsInsideTheWorkerLambdaWithRoomForTheClaimFallbackAndSaves() {
        Duration workerTimeout = Duration.ofSeconds(number("resource \"aws_lambda_function\" \"worker\"", "timeout"));

        assertThat(GroqTriageProvider.CALL_BUDGET.plus(SAVE_AND_FALLBACK_MARGIN)).isLessThanOrEqualTo(workerTimeout);
        assertThat(TriageJobRepository.CLAIM_LEASE).isGreaterThan(workerTimeout);
        assertThat(Duration.ofSeconds(number("resource \"aws_sqs_queue\" \"triage\"", "visibility_timeout_seconds")))
                .isGreaterThan(TriageJobRepository.CLAIM_LEASE);
    }

    @Test
    void theWorkersFinalDeliveryIsSqsMaxReceiveCount() {
        assertThat(number("resource \"aws_sqs_queue\" \"triage\"", "maxReceiveCount")).isEqualTo(TriageWorker.MAX_DELIVERIES);
    }

    private static long number(String block, String key) {
        int start = TERRAFORM.indexOf(block);
        assertThat(start).as(block).isNotNegative();
        Matcher matcher = Pattern.compile("\\b" + key + "\\s*=\\s*(\\d+)").matcher(TERRAFORM);
        assertThat(matcher.find(start)).as(key + " in " + block).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static String readTerraform() {
        try {
            return Files.readString(Path.of("..", "infra", "terraform", "main.tf"));
        } catch (IOException exception) {
            throw new IllegalStateException("infra/terraform/main.tf is needed for the timing contract", exception);
        }
    }
}
