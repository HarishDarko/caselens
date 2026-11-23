package com.harishdarko.caselens.triage;

import java.util.Objects;

public final class SemanticValidationException extends RuntimeException {
    /** The check an AI answer failed. Logged with a fallback; it names the rule, never the content that broke it. */
    public enum Check { CATEGORY_OUTSIDE_PACK, UNKNOWN_POLICY, QUOTE_NOT_FOUND, NO_EVIDENCE }

    private final Check check;

    public SemanticValidationException(Check check, String message) {
        super(message);
        this.check = Objects.requireNonNull(check, "Failed check is required");
    }

    public Check check() { return check; }
}
