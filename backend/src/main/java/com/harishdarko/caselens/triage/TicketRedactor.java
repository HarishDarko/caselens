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
     * A labelled identifier: the label, up to two descriptor words (each may follow a ":", "#", "," or "-", as in
     * "payment id: ref PAY-1" or "RFID, card 04A1"), an optional separator (":", "#", "-" or "is"), then a candidate
     * of 4 or more characters. looksLikeIdentifier decides whether the candidate is redacted.
     */
    private static final Pattern LABELLED_TOKEN = Pattern.compile(
            "(?i)\\b(payment\\s*id|payment\\s*ref(?:erence)?s?|transaction\\s*id|rfid)(?![A-Za-z0-9_])"
            + "((?:\\s*[:#,-]?\\s*(?:" + DESCRIPTOR_WORDS + ")(?![A-Za-z0-9_])){0,2})"
            + "\\s*(?:([:#-])|(is)(?![A-Za-z0-9_]))?\\s*"
            + "([A-Za-z0-9_][A-Za-z0-9_-]{3,})");
    /**
     * More identifiers in a list after a redacted one: "payment references PAY-1004 and PAY-1005". Only a candidate
     * with a digit and the same leading letters as the redacted identifier counts (see sameFamily), so "PAY-1 and
     * CHG-CA-1042" keeps the charger.
     */
    private static final Pattern LIST_TAIL = Pattern.compile(
            "(?i)(?:\\s*,\\s*(?:(?:and|or)\\s+)?|\\s+(?:and|or|&)\\s+)([A-Za-z0-9_][A-Za-z0-9_-]{3,})");
    private static final Pattern LEADING_LETTERS = Pattern.compile("^[A-Za-z]*");
    private static final Pattern LOWERCASE_HEX = Pattern.compile("[0-9a-f]{8,}");
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
        result = redactLabelled(result, state);
        return replace(result, PHONE, match -> state.placeholder("PHONE", digitsOnly(match.group())));
    }

    /**
     * Replaces each labelled identifier with its placeholder, keeping the label and descriptor words (separators are
     * dropped, as before: "Transaction id: TX-55AA" becomes "Transaction id [TRANSACTION_ID_1]"). Identifiers listed
     * right after a redacted one get the same type. A match whose candidate is a plain word is left as written.
     */
    private String redactLabelled(String input, State state) {
        Matcher match = LABELLED_TOKEN.matcher(input);
        StringBuilder output = new StringBuilder();
        int position = 0;
        while (position < input.length() && match.find(position)) {
            output.append(input, position, match.start());
            String candidate = match.group(5);
            boolean afterSeparator = match.group(3) != null && !"-".equals(match.group(3)) && match.group(2).isBlank();
            if (!looksLikeIdentifier(candidate, afterSeparator)) {
                output.append(match.group());
                position = match.end();
                continue;
            }
            String label = match.group(1);
            String lower = label.toLowerCase(Locale.ROOT);
            String type = lower.startsWith("transaction") ? "TRANSACTION_ID" : lower.startsWith("payment") ? "PAYMENT_ID" : "RFID";
            String words = match.group(2).replaceAll("[:#,-]", " ").strip().replaceAll("\\s+", " ");
            String descriptor = words.isEmpty() ? "" : " " + words;
            String is = match.group(4) == null ? "" : " " + match.group(4);
            output.append(label).append(descriptor).append(is).append(' ').append(state.placeholder(type, candidate));
            position = match.end();
            Matcher tail = LIST_TAIL.matcher(input);
            while (position < input.length() && tail.region(position, input.length()).lookingAt()
                    && sameFamily(candidate, tail.group(1))) {
                output.append(input, position, tail.start(1)).append(state.placeholder(type, tail.group(1)));
                position = tail.end();
            }
        }
        output.append(input, position, input.length());
        return output.toString();
    }

    static boolean looksLikeIdentifier(String candidate) {
        return looksLikeIdentifier(candidate, false);
    }

    /**
     * After an explicit label, a candidate is an identifier unless it reads as a plain word: a descriptor word, or a
     * word with lowercase letters and no digit, underscore or hyphen ("RFID card is rejected", "payment id missing").
     * So "04A1B2C3D4", "RFID-DEMO-A19", "ABCDEFGH", "ABCD-EFGH" and "pi_3NxYz" are all redacted. A lowercase word is
     * still an identifier when a ":" or "#" puts it right after the label ("RFID: deadbeef", "payment id: abcdefgh"),
     * or when it is 8 or more lowercase hex characters ("RFID deadbeef01").
     */
    static boolean looksLikeIdentifier(String candidate, boolean afterSeparator) {
        if (DESCRIPTOR.matcher(candidate).matches()) return false;
        if (candidate.chars().anyMatch(c -> Character.isDigit(c) || c == '_' || c == '-')) return true;
        if (afterSeparator || LOWERCASE_HEX.matcher(candidate).matches()) return true;
        return candidate.equals(candidate.toUpperCase(Locale.ROOT));
    }

    /** A listed identifier with a digit and the same leading letters as the redacted one ("PAY-DEMO-1004", "PAY-DEMO-1005"). */
    private static boolean sameFamily(String redacted, String listed) {
        if (listed.chars().noneMatch(Character::isDigit)) return false;
        Matcher prefix = LEADING_LETTERS.matcher(redacted);
        String letters = prefix.find() ? prefix.group() : "";
        if (letters.isEmpty()) return Character.isDigit(listed.charAt(0));
        return listed.regionMatches(true, 0, letters, 0, letters.length())
                && (listed.length() == letters.length() || !Character.isLetter(listed.charAt(letters.length())));
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
