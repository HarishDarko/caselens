package com.harishdarko.caselens.triage;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JpaModelInvocationRecorder implements ModelInvocationRecorder {
    private final ModelInvocationRepository repository;

    public JpaModelInvocationRecorder(ModelInvocationRepository repository) {
        this.repository = repository;
    }

    // Its own transaction, so a failed insert here can never roll back or poison the caller's work.
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ModelInvocationRecord invocation) {
        repository.save(ModelInvocation.from(invocation));
    }
}
