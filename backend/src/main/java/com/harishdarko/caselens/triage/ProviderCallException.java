package com.harishdarko.caselens.triage;

public final class ProviderCallException extends RuntimeException {
    private final String errorCode;

    public ProviderCallException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public ProviderCallException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() { return errorCode; }
}
