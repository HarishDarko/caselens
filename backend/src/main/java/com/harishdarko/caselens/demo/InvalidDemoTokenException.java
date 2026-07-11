package com.harishdarko.caselens.demo;

public final class InvalidDemoTokenException extends RuntimeException {
    public InvalidDemoTokenException() {
        super("Invalid or expired demo session");
    }
}
