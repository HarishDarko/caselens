package com.harishdarko.caselens.triage;

public final class QueuePublishException extends RuntimeException {
    private final String errorCode;
    public QueuePublishException(String errorCode) { super("Queue publish failed"); this.errorCode = errorCode; }
    public String errorCode() { return errorCode; }
}
