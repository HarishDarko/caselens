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
 * The worker's time arithmetic against the deployed settings: the worker Lambda and the triage queue in
 * infra/terraform/main.tf, and the Hikari connect timeout in application-lambda.yml.
 *
 * <ul>
 * <li>The provider call is clamped to the Lambda's remaining time less POST_CALL_RESERVE, and the reserve must cover a
 * fresh database connection (the invocation record runs in its own transaction) plus the fallback and the commit.
 * Each provider's own budget plus the reserve also fits the Lambda when nothing before the call took any time.</li>
 * <li>The claim lease outlives the Lambda, and the visibility timeout outlives the lease, so a redelivery never meets
 * the live claim of a worker that is still running.</li>
 * <li>A rate-limited job is retried by at most one SQS redelivery, so a visitor waits at most one visibility timeout
 * plus one worker run, inside the page's 150 s wait; and a limit falls back at once when it would still stand after
 * the latest moment the redelivery's own call could wait for it, measured from the first delivery's receipt.</li>
 * <li>The worker's last delivery is SQS's maxReceiveCount, so no failure the worker handles reaches the DLQ.</li>
 * </ul>
 */
class WorkerTimingContractTest {
    private static final Duration PAGE_WAIT = Duration.ofSeconds(150);
    private static final Duration MIN_COMMIT_TIME = Duration.ofSeconds(3);
    private static final String TERRAFORM = read(Path.of("..", "infra", "terraform", "main.tf"));
    private static final String LAMBDA_PROFILE = read(Path.of("src", "main", "resources", "application-lambda.yml"));

    @Test
    void theProviderCallLeavesTheReserveTheSavesNeed() {
        Duration workerTimeout = Duration.ofSeconds(number(block("resource \"aws_lambda_function\" \"worker\""), "timeout"));
        Duration hikariConnect = Duration.ofMillis(number(LAMBDA_PROFILE, "connection-timeout"));

        assertThat(TriageWorker.POST_CALL_RESERVE).isGreaterThanOrEqualTo(hikariConnect.plus(MIN_COMMIT_TIME));
        assertThat(GroqTriageProvider.CALL_BUDGET.plus(TriageWorker.POST_CALL_RESERVE)).isLessThanOrEqualTo(workerTimeout);
        assertThat(GeminiTriageProvider.CALL_BUDGET.plus(TriageWorker.POST_CALL_RESERVE)).isLessThanOrEqualTo(workerTimeout);
    }

    @Test
    void theClaimLeaseOutlivesTheLambdaAndTheVisibilityTimeoutOutlivesTheLease() {
        Duration workerTimeout = Duration.ofSeconds(number(block("resource \"aws_lambda_function\" \"worker\""), "timeout"));
        Duration visibility = Duration.ofSeconds(number(block("resource \"aws_sqs_queue\" \"triage\""), "visibility_timeout_seconds"));

        assertThat(TriageJobRepository.CLAIM_LEASE).isGreaterThan(workerTimeout);
        assertThat(visibility).isGreaterThan(TriageJobRepository.CLAIM_LEASE);
    }

    @Test
    void aRateLimitedVisitorWaitsAtMostOneRedeliveryInsideThePagesWait() {
        Duration workerTimeout = Duration.ofSeconds(number(block("resource \"aws_lambda_function\" \"worker\""), "timeout"));
        Duration visibility = Duration.ofSeconds(number(block("resource \"aws_sqs_queue\" \"triage\""), "visibility_timeout_seconds"));

        assertThat(TriageWorker.RATE_LIMIT_DELIVERIES).isEqualTo(2);
        assertThat(visibility.multipliedBy(TriageWorker.RATE_LIMIT_DELIVERIES - 1).plus(workerTimeout))
                .isLessThanOrEqualTo(PAGE_WAIT);
        assertThat(RateLimitWait.REDELIVERY_DELAY).isEqualTo(visibility);
    }

    @Test
    void theWorstCaseFromFirstDeliveryToFinalAnswerStaysInsideThePagesWait() {
        Duration workerTimeout = Duration.ofSeconds(number(block("resource \"aws_lambda_function\" \"worker\""), "timeout"));
        Duration visibility = Duration.ofSeconds(number(block("resource \"aws_sqs_queue\" \"triage\""), "visibility_timeout_seconds"));

        // The redelivery starts a visibility timeout after the first receipt, waits in its call for at most the call
        // budget, then needs the post-call reserve for the saves: 90 + 45 + 14 = 149 s.
        for (Duration callBudget : java.util.List.of(GroqTriageProvider.CALL_BUDGET, GeminiTriageProvider.CALL_BUDGET)) {
            assertThat(visibility.plus(callBudget).plus(TriageWorker.POST_CALL_RESERVE)).isLessThanOrEqualTo(PAGE_WAIT);
        }
        assertThat(visibility.plus(workerTimeout)).isLessThanOrEqualTo(PAGE_WAIT);
        // Both providers use the same margins, which RETRY_HORIZON is built from.
        assertThat(GeminiTriageProvider.CALL_BUDGET).isEqualTo(GroqTriageProvider.CALL_BUDGET);
        assertThat(GeminiTriageProvider.CONNECT_ALLOWANCE).isEqualTo(GroqTriageProvider.CONNECT_ALLOWANCE);
        assertThat(GeminiTriageProvider.MIN_RETRY_TIMEOUT).isEqualTo(GroqTriageProvider.MIN_RETRY_TIMEOUT);
        // A limit that clears at the horizon is waited out by the redelivery with its retry still inside the budget.
        assertThat(RateLimitWait.RETRY_HORIZON.plus(RateLimitWait.SLEEP_OVERSHOOT).plus(GroqTriageProvider.CONNECT_ALLOWANCE)
                .plus(GroqTriageProvider.MIN_RETRY_TIMEOUT)).isEqualTo(visibility.plus(GroqTriageProvider.CALL_BUDGET));
        assertThat(RateLimitWait.RETRY_HORIZON).isEqualTo(Duration.ofSeconds(127));
        assertThat(TriageRequest.MIN_ATTEMPT_BUDGET).isEqualTo(GroqTriageProvider.CONNECT_ALLOWANCE.plusSeconds(1));
    }

    @Test
    void theWorkersFinalDeliveryIsSqsMaxReceiveCount() {
        assertThat(number(block("resource \"aws_sqs_queue\" \"triage\""), "maxReceiveCount")).isEqualTo(TriageWorker.MAX_DELIVERIES);
        assertThat(TriageWorker.RATE_LIMIT_DELIVERIES).isLessThanOrEqualTo(TriageWorker.MAX_DELIVERIES);
    }

    @Test
    void readsOnlyTheNamedTerraformBlock() {
        String worker = block("resource \"aws_lambda_function\" \"worker\"");

        assertThat(worker).contains("WorkerHandler").doesNotContain("RelayHandler").endsWith("}");
    }

    /** The text of one top-level Terraform block, from its header to its matching closing brace. */
    private static String block(String header) {
        int start = TERRAFORM.indexOf(header);
        assertThat(start).as(header).isNotNegative();
        int open = TERRAFORM.indexOf('{', start);
        int depth = 0;
        for (int index = open; index < TERRAFORM.length(); index++) {
            char current = TERRAFORM.charAt(index);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) return TERRAFORM.substring(start, index + 1);
        }
        throw new AssertionError("Unclosed block: " + header);
    }

    private static long number(String text, String key) {
        Matcher matcher = Pattern.compile("\\b" + key + "\\s*[=:]\\s*(\\d+)").matcher(text);
        assertThat(matcher.find()).as(key).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException(path + " is needed for the timing contract", exception);
        }
    }
}
