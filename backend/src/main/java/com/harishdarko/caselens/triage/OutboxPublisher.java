package com.harishdarko.caselens.triage;

@FunctionalInterface
public interface OutboxPublisher {
    void publish(OutboxEvent event);
}
