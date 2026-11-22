package com.harishdarko.caselens.triage;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@ConditionalOnProperty(name = "caselens.queue.enabled", havingValue = "true")
public class SqsQueueConfiguration {
    static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(6);

    @Bean
    SqsClient sqsClient(@Value("${aws.region:ca-central-1}") String region,
            @Value("${aws.endpoint:}") String endpoint) {
        // A stalled SQS call must not hold a Lambda invocation or a database row lock for long: one attempt gets
        // 3 s and the whole call, retries included, 6 s. A publish that times out stays in the outbox for the relay.
        var builder = SqsClient.builder().region(Region.of(region))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .build());
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
            AwsCredentialsProvider localCredentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
            builder.credentialsProvider(localCredentials);
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    @Bean
    OutboxPublisher sqsOutboxPublisher(SqsClient client, @Value("${caselens.triage-queue-url}") String queueUrl) {
        return new SqsOutboxPublisher(client, queueUrl);
    }
}
