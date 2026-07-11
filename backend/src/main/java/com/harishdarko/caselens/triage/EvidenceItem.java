package com.harishdarko.caselens.triage;

import java.util.Objects;

public record EvidenceItem(String quote, String meaning) {
    public EvidenceItem {
        quote = requiredBounded(quote, "Evidence quote", 3, 400);
        meaning = requiredBounded(meaning, "Evidence meaning", 3, 240);
    }

    private static String requiredBounded(String value, String label, int minimum, int maximum) {
        Objects.requireNonNull(value, label + " is required");
        if (value.isBlank() || value.length() < minimum || value.length() > maximum) {
            throw new IllegalArgumentException(label + " must contain " + minimum + " to " + maximum + " characters");
        }
        return value;
    }
}
