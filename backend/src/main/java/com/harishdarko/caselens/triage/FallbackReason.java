package com.harishdarko.caselens.triage;

/**
 * Why the rules fallback decided a ticket instead of the AI, in words a reviewer can act on. It is derived from the
 * sanitized error code of the failed provider call (see TriageEngine and the model-invocation record), so it never
 * carries ticket text or model output.
 */
public enum FallbackReason {
    /** The provider could not be reached, timed out, rejected the request or failed in another way. */
    PROVIDER_UNAVAILABLE,
    /** The provider answered 429 (on the final delivery), or 413: the request is larger than the token rate limit. */
    PROVIDER_RATE_LIMITED,
    /** The daily AI call budget, or this client's share of it, is spent. */
    BUDGET_SPENT,
    /** The answer was readable but failed the semantic checks (category, policy or evidence quotes). */
    ANSWER_FAILED_CHECKS,
    /** The answer was empty, too large or did not follow the response schema. */
    ANSWER_UNREADABLE;

    public static FallbackReason fromErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) return PROVIDER_UNAVAILABLE;
        return switch (errorCode) {
            case "HTTP_429", "HTTP_413" -> PROVIDER_RATE_LIMITED;
            case BudgetedTriageProvider.BUDGET_EXHAUSTED, BudgetedTriageProvider.SHARE_EXHAUSTED -> BUDGET_SPENT;
            case TriageEngine.SEMANTIC_VALIDATION -> ANSWER_FAILED_CHECKS;
            case "SCHEMA_FAILURE", "RESPONSE_TOO_LARGE" -> ANSWER_UNREADABLE;
            default -> PROVIDER_UNAVAILABLE;
        };
    }
}
