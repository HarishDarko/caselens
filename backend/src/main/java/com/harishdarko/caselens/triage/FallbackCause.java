package com.harishdarko.caselens.triage;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Why one triage fell back to the rules, for the worker's log line and the result. Every field is a fixed code
 * from this application: the plain reason, the sanitized provider error code, the semantic check that failed (null
 * unless the answer failed validation), the industry pack id and the provider name. Never ticket text, evidence
 * quotes or model output. A code that is not of the expected shape is replaced, so a future provider that puts
 * free text in its error code cannot leak it into the logs.
 */
public record FallbackCause(FallbackReason reason, String errorCode, String failedCheck, String pack, String provider) {
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{1,64}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    static final String UNRECOGNIZED = "UNRECOGNIZED";

    public FallbackCause {
        Objects.requireNonNull(reason, "Fallback reason is required");
        errorCode = code(errorCode);
        failedCheck = failedCheck == null ? null : code(failedCheck);
        pack = name(pack);
        provider = name(provider);
    }

    static FallbackCause of(String errorCode, String failedCheck, String pack, String provider) {
        String safeCode = code(errorCode);
        return new FallbackCause(FallbackReason.fromErrorCode(safeCode), safeCode, failedCheck, pack, provider);
    }

    private static String code(String value) {
        return value != null && CODE.matcher(value).matches() ? value : UNRECOGNIZED;
    }

    private static String name(String value) {
        return value != null && NAME.matcher(value).matches() ? value : "unknown";
    }
}
