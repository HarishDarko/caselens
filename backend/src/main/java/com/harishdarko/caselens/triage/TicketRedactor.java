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
    /**
     * A labelled identifier: the label, up to two optional descriptor words ("RFID card", "payment id number",
     * "transaction id ref code"), an optional separator, then the identifier. The identifier must hold a digit, so a
     * plain word after the label ("RFID card is rejected") is left alone, and the value after a descriptor
     * ("RFID card 04A1B2C3D4") is the part that gets redacted.
     */
    private static final Pattern LABELLED_TOKEN = Pattern.compile(
            "(?i)\\b(payment\\s*id|transaction\\s*id|rfid)"
            + "((?:\\s+(?:tokens?|cards?|badges?|numbers?|no\\.?|ref|reference|codes?|value)(?![A-Z0-9]))"
            + "(?:\\s+(?:number|no\\.?|code|value)(?![A-Z0-9]))?)?"
            + "\\s*[:#-]?\\s*((?=[A-Z0-9-]*[0-9])[A-Z0-9-]{4,})");
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
            String label = match.group(1);
            String type = label.toLowerCase(Locale.ROOT).startsWith("transaction") ? "TRANSACTION_ID"
                    : label.toLowerCase(Locale.ROOT).startsWith("payment") ? "PAYMENT_ID" : "RFID";
            String descriptor = match.group(2) == null ? "" : " " + match.group(2).strip().replaceAll("\\s+", " ");
            return label + descriptor + " " + state.placeholder(type, match.group(3));
        });
        return replace(result, PHONE, match -> state.placeholder("PHONE", digitsOnly(match.group())));
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
