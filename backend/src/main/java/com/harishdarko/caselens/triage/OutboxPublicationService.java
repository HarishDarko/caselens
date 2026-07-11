package com.harishdarko.caselens.triage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxPublicationService {
    private final OutboxEventRepository events;

    public OutboxPublicationService(OutboxEventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(OutboxEvent event) {
        events.save(event);
    }
}
