package com.harishdarko.caselens.demo;

public final class InvalidPasscodeException extends RuntimeException {
    public InvalidPasscodeException() { super("Unauthorized"); }
}
