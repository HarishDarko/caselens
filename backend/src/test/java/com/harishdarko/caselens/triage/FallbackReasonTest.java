package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class FallbackReasonTest {
    @ParameterizedTest
    @CsvSource({
        "HTTP_429, PROVIDER_RATE_LIMITED",
        "AI_BUDGET_EXHAUSTED, BUDGET_SPENT",
        "AI_BUDGET_SHARE_EXHAUSTED, BUDGET_SPENT",
        "SEMANTIC_VALIDATION, ANSWER_FAILED_CHECKS",
        "SCHEMA_FAILURE, ANSWER_UNREADABLE",
        "RESPONSE_TOO_LARGE, ANSWER_UNREADABLE",
        "HTTP_503, PROVIDER_UNAVAILABLE",
        "HTTP_400, PROVIDER_UNAVAILABLE",
        "PROVIDER_IO, PROVIDER_UNAVAILABLE",
        "AUTHENTICATION_FAILURE, PROVIDER_UNAVAILABLE",
        "TRANSIENT_FAILURE, PROVIDER_UNAVAILABLE",
        "INTERRUPTED, PROVIDER_UNAVAILABLE",
        "PROVIDER_FAILURE, PROVIDER_UNAVAILABLE"
    })
    void mapsEachSanitizedErrorCodeToOnePlainReason(String errorCode, FallbackReason expected) {
        assertThat(FallbackReason.fromErrorCode(errorCode)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void aMissingErrorCodeMeansTheProviderWasUnavailable(String errorCode) {
        assertThat(FallbackReason.fromErrorCode(errorCode)).isEqualTo(FallbackReason.PROVIDER_UNAVAILABLE);
    }
}
