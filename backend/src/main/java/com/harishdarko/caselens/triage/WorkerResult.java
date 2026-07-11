package com.harishdarko.caselens.triage;

public record WorkerResult(WorkerResultStatus status, String errorCode) {
    public static WorkerResult ack() { return new WorkerResult(WorkerResultStatus.ACK, null); }
    public static WorkerResult duplicate() { return new WorkerResult(WorkerResultStatus.ACK_DUPLICATE, null); }
    public static WorkerResult retry(String code) { return new WorkerResult(WorkerResultStatus.RETRY, code); }
    public static WorkerResult terminal(String code) { return new WorkerResult(WorkerResultStatus.TERMINAL_FAILURE, code); }
}
