package com.harishdarko.caselens.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harishdarko.caselens.triage.PackCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcOperations;

/** The page calls GET /api/demo/session in the background when it loads, to wake the API and the database early. */
class DemoSessionWarmUpTest {
    private final PackCatalog packs = PackCatalog.builtIn();
    private final DemoScenarios scenarios = new DemoScenarios(DemoScenarios.fromClasspath(packs.ids()), mock(DemoWorkspaceAccess.class));
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final DemoSessionController controller = new DemoSessionController(null, packs, scenarios, jdbc);

    @Test
    void theIndustryListAlsoWakesTheDatabaseWithOneCheapQuery() {
        controller.options();

        verify(jdbc).queryForObject("select 1", Integer.class);
    }

    @Test
    void theIndustryListStillAnswersWhenTheDatabaseIsDown() {
        when(jdbc.queryForObject("select 1", Integer.class)).thenThrow(new CannotGetJdbcConnectionException("down"));

        assertThat(controller.options().industries()).hasSize(3);
    }
}
