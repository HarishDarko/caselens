package com.harishdarko.caselens.triage;

@FunctionalInterface
public interface ModelInvocationRecorder {
    void record(ModelInvocationRecord invocation);
}
