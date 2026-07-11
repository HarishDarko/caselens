package com.harishdarko.caselens.triage;

import org.springframework.dao.TransientDataAccessException;

public final class TriageFailureClassifier {
    public FailureClassification classify(Throwable failure) {
        if (failure instanceof TransientDataAccessException) return new FailureClassification(true, "DATABASE_TRANSIENT");
        if (failure instanceof ProviderCallException provider) {
            if ("AUTHENTICATION_FAILURE".equals(provider.errorCode())) return new FailureClassification(false, "PROVIDER_AUTHENTICATION");
            if (provider.errorCode().startsWith("HTTP_429")) return new FailureClassification(true, "PROVIDER_RATE_LIMIT");
            if (provider.errorCode().startsWith("HTTP_5") || "PROVIDER_IO".equals(provider.errorCode())
                    || "TIMEOUT".equals(provider.errorCode())) return new FailureClassification(true, "PROVIDER_TIMEOUT");
            return new FailureClassification(false, "PROVIDER_FAILURE");
        }
        if (failure instanceof IllegalArgumentException) return new FailureClassification(false, "INVALID_EVENT");
        return new FailureClassification(false, "SEMANTIC_VALIDATION_FAILED_AFTER_FALLBACK");
    }
}
