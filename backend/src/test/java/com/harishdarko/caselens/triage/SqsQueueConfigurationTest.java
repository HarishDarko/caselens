package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;

class SqsQueueConfigurationTest {
    @Test
    void boundsEachSqsCallSoAStalledRequestCannotHoldTheInvocation() {
        try (SqsClient client = new SqsQueueConfiguration().sqsClient("ca-central-1", "http://localhost:4566")) {
            var override = client.serviceClientConfiguration().overrideConfiguration();
            assertThat(override.apiCallAttemptTimeout()).contains(Duration.ofSeconds(3));
            assertThat(override.apiCallTimeout()).contains(Duration.ofSeconds(6));
        }
    }
}
