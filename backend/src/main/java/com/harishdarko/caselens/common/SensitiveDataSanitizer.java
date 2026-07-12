package com.harishdarko.caselens.common;

import java.util.regex.Pattern;

public final class SensitiveDataSanitizer {
    private static final int MAX_LOG_VALUE_LENGTH = 240;
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[\\w.%+-]+@[\\w.-]+\\.[a-z]{2,}\\b");
    private static final Pattern CARD = Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?1[ -]?)?(?:\\(\\d{3}\\)|\\d{3})[ .-]?\\d{3}[ .-]?\\d{4}(?!\\d)");

    private SensitiveDataSanitizer() {}

    public static String text(String value) {
        if (value == null) return "[null]";
        String sanitized = EMAIL.matcher(value).replaceAll("[redacted-email]");
        sanitized = CARD.matcher(sanitized).replaceAll("[redacted-card]");
        sanitized = PHONE.matcher(sanitized).replaceAll("[redacted-phone]");
        return sanitized.length() <= MAX_LOG_VALUE_LENGTH
                ? sanitized : sanitized.substring(0, MAX_LOG_VALUE_LENGTH);
    }
}
