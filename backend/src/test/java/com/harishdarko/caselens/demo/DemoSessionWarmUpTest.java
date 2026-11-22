package com.harishdarko.caselens.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.mockito.Mockito.times;

import com.harishdarko.caselens.triage.PackCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcOperations;

/** The page calls GET /api/demo/session in the background when it loads, to wake the API and the database early. */
class DemoSessionWarmUpTest {
    private final PackCatalog packs = PackCatalog.builtIn();
    private final DemoScenarios scenarios = new DemoScenarios(DemoScenarios.fromClasspath(packs.ids()), mock(DemoWorkspaceAccess.class));
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2040-07-11T12:00:00Z"));
    private final DemoSessionController controller = new DemoSessionController(null, packs, scenarios, jdbc, clock);

    @Test
    void theIndustryListAlsoWakesTheDatabaseWithOneCheapQuery() {
        controller.options();

        verify(jdbc).queryForObject("select 1", Integer.class);
    }

    @Test
    void aBurstOfCallsRunsTheWakeUpQueryOncePerInterval() {
        for (int call = 0; call < 50; call++) controller.options();
        verify(jdbc, times(1)).queryForObject("select 1", Integer.class);

        clock.advance(DemoSessionController.WARM_UP_INTERVAL.minusSeconds(1));
        controller.options();
        verify(jdbc, times(1)).queryForObject("select 1", Integer.class);

        clock.advance(Duration.ofSeconds(1));
        controller.options();
        verify(jdbc, times(2)).queryForObject("select 1", Integer.class);
    }

    @Test
    void theIndustryListStillAnswersWhenTheDatabaseIsDown() {
        when(jdbc.queryForObject("select 1", Integer.class)).thenThrow(new CannotGetJdbcConnectionException("down"));

        assertThat(controller.options().industries()).hasSize(3);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
