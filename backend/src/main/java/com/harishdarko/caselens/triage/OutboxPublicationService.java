package com.harishdarko.caselens.triage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the outcome of the publish that runs right after the request transaction commits. It uses the same
 * conditional updates as the relay instead of saving the detached event, so it can never overwrite a row that the
 * relay published (or counted a failure on) in the meantime. It runs after the request transaction committed, so
 * it needs a transaction of its own to commit anything.
 */
@Service
public class OutboxPublicationService {
    private final OutboxEventRepository events;

    public OutboxPublicationService(OutboxEventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(OutboxEvent event) {
        if (event.getPublishedAt() != null) {
            events.markPublished(event.getId(), event.getPublishedAt());
        } else {
            events.recordPublishFailure(event.getId(), event.getLastErrorCode());
        }
    }
}
