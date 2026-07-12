package com.harishdarko.caselens.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveDataSanitizerTest {
    @Test
    void removesContactPaymentAndOversizedBodyValuesBeforeLogging() {
        String input = "email=reviewer@example.test phone=+1 555-010-0188 card=4111 1111 1111 1111 "
                + " body=" + "x".repeat(300);

        String sanitized = SensitiveDataSanitizer.text(input);

        assertThat(sanitized).contains("[redacted-email]", "[redacted-phone]", "[redacted-card]")
                .doesNotContain("reviewer@example.test", "+1 555-010-0188", "4111 1111 1111 1111")
                .hasSizeLessThanOrEqualTo(240);
    }
}
