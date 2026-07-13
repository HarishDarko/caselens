package com.harishdarko.caselens.triage;

final class SyntheticDemoRetryException extends RuntimeException {
    SyntheticDemoRetryException() {
        super("Synthetic demo provider timeout");
    }
}
