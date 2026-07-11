package com.harishdarko.caselens.triage;

public class NoopOutboxPublisher implements OutboxPublisher {
    @Override public void publish(OutboxEvent event) { }
}
