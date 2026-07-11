package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {
    @Mock OutboxEventRepository events;
    @Mock OutboxPublisher publisher;

    @Test
    void publishesAndMarksOnlyAcknowledgedEvents() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-11T15:00:00Z"), ZoneOffset.UTC);
        OutboxEvent success = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "corr-1", clock.instant());
        OutboxEvent failed = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "corr-2", clock.instant());
        when(events.claimUnpublished(20)).thenReturn(List.of(success, failed));
        org.mockito.Mockito.doNothing().when(publisher).publish(success);
        doThrow(new QueuePublishException("QUEUE_TRANSIENT")).when(publisher).publish(failed);

        int published = new OutboxRelay(events, publisher, clock).publishBatch(20);

        assertThat(published).isOne();
        assertThat(success.getPublishedAt()).isEqualTo(clock.instant());
        assertThat(failed.getPublishedAt()).isNull();
        assertThat(failed.getPublishAttempts()).isEqualTo(1);
        assertThat(failed.getLastErrorCode()).isEqualTo("QUEUE_TRANSIENT");
        verify(events, org.mockito.Mockito.times(2)).save(any(OutboxEvent.class));
    }
}
