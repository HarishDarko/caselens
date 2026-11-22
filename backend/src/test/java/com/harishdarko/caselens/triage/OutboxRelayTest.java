package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-11T15:00:00Z"), ZoneOffset.UTC);

    @Mock OutboxEventRepository events;
    @Mock OutboxPublisher publisher;

    @Test
    void publishesEachEventInItsOwnTransactionAndRecordsOutcomesWithConditionalUpdates() {
        OutboxEvent success = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "corr-1", clock.instant());
        OutboxEvent failed = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "corr-2", clock.instant());
        when(events.findPublishCandidates(20, OutboxRelay.MAX_PUBLISH_ATTEMPTS))
                .thenReturn(List.of(success.getId(), failed.getId()));
        when(events.lockUnpublished(success.getId())).thenReturn(Optional.of(success));
        when(events.lockUnpublished(failed.getId())).thenReturn(Optional.of(failed));
        doThrow(new QueuePublishException("QUEUE_TRANSIENT")).when(publisher).publish(failed);
        CountingTransactions transactions = new CountingTransactions();

        int published = new OutboxRelay(events, publisher, clock, TriageMetrics.noop(), transactions).publishBatch(20);

        assertThat(published).isOne();
        assertThat(transactions.count).isEqualTo(2);
        verify(events).markPublished(success.getId(), clock.instant());
        verify(events).recordPublishFailure(failed.getId(), "QUEUE_TRANSIENT");
        verify(events, never()).save(any());
    }

    @Test
    void skipsAnEventThatWasPublishedOrLockedSinceTheCandidateQuery() {
        UUID taken = UUID.randomUUID();
        when(events.findPublishCandidates(25, OutboxRelay.MAX_PUBLISH_ATTEMPTS)).thenReturn(List.of(taken));
        when(events.lockUnpublished(taken)).thenReturn(Optional.empty());

        int published = new OutboxRelay(events, publisher, clock).publishBatch(25);

        assertThat(published).isZero();
        verify(publisher, never()).publish(any());
        verify(events, never()).markPublished(any(), any());
    }

    /** Runs each callback directly and counts how many transactions the relay asked for. */
    private static final class CountingTransactions implements TransactionOperations {
        private int count;
        private final List<Object> results = new ArrayList<>();

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            count++;
            T result = action.doInTransaction(org.mockito.Mockito.mock(TransactionStatus.class));
            results.add(result);
            return result;
        }
    }
}
