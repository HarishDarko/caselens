package com.harishdarko.caselens;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Testcontainers(disabledWithoutDocker = true)
class LocalStackSqsIntegrationTest {
    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.6"))
            .withServices(LocalStackContainer.Service.SQS);

    @Test
    void movesTheFifthUnacknowledgedReceiveToTheConfiguredDlq() throws Exception {
        try (SqsClient client = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS))
                .region(Region.of("ca-central-1"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            String suffix = UUID.randomUUID().toString();
            String dlqUrl = client.createQueue(CreateQueueRequest.builder().queueName("dlq-" + suffix).build()).queueUrl();
            String dlqArn = client.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(dlqUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN).build()).attributes().get(QueueAttributeName.QUEUE_ARN);
            String queueUrl = client.createQueue(CreateQueueRequest.builder().queueName("queue-" + suffix)
                    .attributes(Map.of(QueueAttributeName.REDRIVE_POLICY,
                            "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"5\"}",
                            QueueAttributeName.VISIBILITY_TIMEOUT, "0"))
                    .build()).queueUrl();

            client.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody("synthetic-event").build());
            ReceiveMessageRequest receive = ReceiveMessageRequest.builder().queueUrl(queueUrl)
                    .maxNumberOfMessages(1).visibilityTimeout(0).build();
            for (int attempt = 0; attempt < 8; attempt++) {
                client.receiveMessage(receive);
                Thread.sleep(100);
            }

            assertThat(client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(dlqUrl)
                    .maxNumberOfMessages(1).waitTimeSeconds(1).build()).messages()).hasSize(1);
        }
    }
}
