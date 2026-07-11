package com.harishdarko.caselens.triage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
@ConditionalOnProperty(name = "caselens.queue.enabled", havingValue = "true")
public class OutboxRelayScheduler {
    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${caselens.outbox.relay-delay-ms:5000}")
    public void publish() {
        relay.publishBatch(25);
    }
}
