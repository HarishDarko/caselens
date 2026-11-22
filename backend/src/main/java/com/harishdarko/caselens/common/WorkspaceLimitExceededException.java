package com.harishdarko.caselens.common;

/** A demo workspace already holds as many of something (tickets, feedback) as one workspace may hold. */
public final class WorkspaceLimitExceededException extends RuntimeException {
    private final String item;
    private final int limit;

    public WorkspaceLimitExceededException(String item, int limit) {
        super("Workspace " + item + " limit reached");
        this.item = item;
        this.limit = limit;
    }

    public String item() { return item; }
    public int limit() { return limit; }
}
