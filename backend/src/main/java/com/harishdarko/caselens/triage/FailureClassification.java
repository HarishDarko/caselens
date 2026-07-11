package com.harishdarko.caselens.triage;

public record FailureClassification(boolean retryable, String errorCode) {}
