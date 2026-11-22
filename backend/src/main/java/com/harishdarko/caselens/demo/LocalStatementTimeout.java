package com.harishdarko.caselens.demo;

import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Sets a statement timeout for the rest of the current transaction on PostgreSQL ({@code SET LOCAL}), so one slow
 * cleanup statement cannot hold the relay function past its own timeout. Other databases (H2 in tests) are skipped.
 */
final class LocalStatementTimeout {
    private final JdbcTemplate jdbc;
    private final long millis;
    private volatile Boolean postgres;

    LocalStatementTimeout(JdbcTemplate jdbc, Duration timeout) {
        this.jdbc = jdbc;
        this.millis = timeout.toMillis();
    }

    /** Call inside a transaction; outside one, SET LOCAL has no effect. */
    void apply() {
        if (jdbc == null || !isPostgres()) return;
        jdbc.execute("SET LOCAL statement_timeout = " + millis);
    }

    private boolean isPostgres() {
        Boolean known = postgres;
        if (known == null) {
            known = Boolean.TRUE.equals(jdbc.execute((java.sql.Connection connection) ->
                    "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())));
            postgres = known;
        }
        return known;
    }
}
