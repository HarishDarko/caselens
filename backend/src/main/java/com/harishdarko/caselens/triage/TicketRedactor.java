package com.harishdarko.caselens.triage;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TicketRedactor {
    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern CARD = Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)");
    /** Words that describe the identifier rather than being it: "RFID card 04A1", "payment id number PAY1". */
    private static final String DESCRIPTOR_WORDS =
            "tokens?|cards?|badges?|tags?|chips?|fobs?|ids?|numbers?|no\\.?|ref|reference|codes?|value";
    private static final Pattern DESCRIPTOR = Pattern.compile("(?i)(?:" + DESCRIPTOR_WORDS + ")");
    /**
     * A labelled identifier: the label, up to two descriptor words, an optional separator (":", "#", "-" or "is"),
     * then a candidate of 4 or more characters. looksLikeIdentifier decides whether the candidate is redacted.
     */
    private static final Pattern LABELLED_TOKEN = Pattern.compile(
            "(?i)\\b(payment\\s*id|transaction\\s*id|rfid)(?![A-Za-z0-9_])"
            + "((?:\\s+(?:" + DESCRIPTOR_WORDS + ")(?![A-Za-z0-9_])){0,2})"
            + "\\s*(?:[:#-]|(is)(?![A-Za-z0-9_]))?\\s*"
            + "([A-Za-z0-9_][A-Za-z0-9_-]{3,})");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\w)(?:\\+?1[\\s.-]?)?(?:\\(?\\d{3}\\)?[\\s.-]?)\\d{3}[\\s.-]?\\d{4}(?!\\w)");

    public RedactedTicket redact(String subject, String message) {
        State state = new State();
        String safeSubject = redactField(subject, state);
        String safeMessage = redactField(message, state);
        return new RedactedTicket(safeSubject, safeMessage);
    }

    private String redactField(String value, State state) {
        String result = replace(value, EMAIL, match -> state.placeholder("EMAIL", match.group()));
        result = replace(result, CARD, match -> state.placeholder("CARD", digitsOnly(match.group())));
        result = replace(result, LABELLED_TOKEN, match -> {
            String candidate = match.group(4);
            if (!looksLikeIdentifier(candidate)) return match.group();
            String label = match.group(1);
            String type = label.toLowerCase(Locale.ROOT).startsWith("transaction") ? "TRANSACTION_ID"
                    : label.toLowerCase(Locale.ROOT).startsWith("payment") ? "PAYMENT_ID" : "RFID";
            String descriptor = match.group(2).isBlank() ? "" : " " + match.group(2).strip().replaceAll("\\s+", " ");
            String is = match.group(3) == null ? "" : " " + match.group(3);
            return label + descriptor + is + " " + state.placeholder(type, candidate);
        });
        return replace(result, PHONE, match -> state.placeholder("PHONE", digitsOnly(match.group())));
    }

    /**
     * After an explicit label, a candidate is an identifier unless it reads as a plain word: a descriptor word, or a
     * word with lowercase letters and no digit, underscore or hyphen ("RFID card is rejected", "payment id missing").
     * So "04A1B2C3D4", "RFID-DEMO-A19", "ABCDEFGH", "ABCD-EFGH" and "pi_3NxYz" are all redacted.
     */
    static boolean looksLikeIdentifier(String candidate) {
        if (DESCRIPTOR.matcher(candidate).matches()) return false;
        if (candidate.chars().anyMatch(c -> Character.isDigit(c) || c == '_' || c == '-')) return true;
        return candidate.equals(candidate.toUpperCase(Locale.ROOT));
    }

    private String replace(String input, Pattern pattern, Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(output, Matcher.quoteReplacement(replacement.apply(matcher)));
        matcher.appendTail(output);
        return output.toString();
    }

    private String digitsOnly(String value) { return value.replaceAll("\\D", ""); }

    private static final class State {
        private final Map<String, Map<String, String>> placeholders = new HashMap<>();

        String placeholder(String type, String raw) {
            Map<String, String> values = placeholders.computeIfAbsent(type, ignored -> new HashMap<>());
            return values.computeIfAbsent(raw.toLowerCase(Locale.ROOT), ignored -> "[" + type + "_" + (values.size() + 1) + "]");
        }
    }
}
