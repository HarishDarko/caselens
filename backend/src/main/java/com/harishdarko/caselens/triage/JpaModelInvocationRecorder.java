package com.harishdarko.caselens.triage;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JpaModelInvocationRecorder implements ModelInvocationRecorder {
    private final ModelInvocationRepository repository;

    public JpaModelInvocationRecorder(ModelInvocationRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void record(ModelInvocationRecord invocation) {
        repository.save(ModelInvocation.from(invocation));
    }
}
