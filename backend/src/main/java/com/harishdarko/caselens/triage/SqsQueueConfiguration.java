package com.harishdarko.caselens.triage;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@ConditionalOnProperty(name = "caselens.queue.enabled", havingValue = "true")
public class SqsQueueConfiguration {
    @Bean
    SqsClient sqsClient(@Value("${aws.region:ca-central-1}") String region,
            @Value("${aws.endpoint:}") String endpoint) {
        var builder = SqsClient.builder().region(Region.of(region));
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
