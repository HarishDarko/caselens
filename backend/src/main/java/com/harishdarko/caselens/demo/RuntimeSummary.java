package com.harishdarko.caselens.demo;

import java.util.Locale;

public record RuntimeSummary(Environment environment, Database database, Queue queue, AiProvider aiProvider) {
    public enum Environment { LOCAL_REVIEW, AWS_DEMO }
    public enum Database { POSTGRESQL, NEON_POSTGRESQL }
    public enum Queue { LOCALSTACK_SQS, AMAZON_SQS }
    public enum AiProvider { GROQ, GEMINI, DETERMINISTIC }

    public static RuntimeSummary from(String environment, String database, String queue, String aiProvider) {
        return new RuntimeSummary(
                parse(Environment.class, environment),
                parse(Database.class, database),
                parse(Queue.class, queue),
                parseProvider(aiProvider));
    }

    private static AiProvider parseProvider(String value) {
        if ("mock".equalsIgnoreCase(value.trim())) return AiProvider.DETERMINISTIC;
        return parse(AiProvider.class, value);
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String value) {
        try {
            return Enum.valueOf(type, value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unsupported runtime label", exception);
        }
    }
}
