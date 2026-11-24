package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harishdarko.caselens.demo.DemoScenario;
import com.harishdarko.caselens.demo.ScenarioCatalog;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The CI gate for the recorded results seeded with every workspace. Each fixture is a real captured model answer, so
 * it must still be a valid answer to the ticket it is seeded on: if a scenario's text changes later, this test fails
 * until the result is captured again, instead of a workspace showing an answer that quotes a ticket that is gone.
 */
class RecordedTriageFixturesTest {
    /** The priority scores the live API computed for each capture (2026-11-24); the scorer must keep giving them. */
    private static final Map<String, Integer> CAPTURED_SCORES = Map.of(
            "charger-offline-site-wide", 60, "connector-fault-single-port", 25, "receipt-request-general", 10,
            "saas-sso-lockout", 85, "saas-api-outage", 90, "saas-custom-field-question", 0,
            "shop-two-payments", 70, "shop-refund-missing", 55, "shop-change-address", 10);

    private final PackCatalog packs = PackCatalog.builtIn();
    private final RecordedTriageCatalog catalog = RecordedTriageCatalog.load(packs, new ObjectMapper());
    private final TicketRedactor redactor = new TicketRedactor();
    private final PriorityScorer scorer = new PriorityScorer();
    private final SemanticValidator validator = new SemanticValidator(PolicyCatalog.defaultCatalog());

    @Test
    void everyPackHasExactlyThreeFixturesThatMatchItsSeededScenarioKeys() {
        assertThat(packs.ids()).containsExactlyInAnyOrder("ev-charging", "saas-support", "e-commerce");
        for (String id : packs.ids()) {
            List<String> seededKeys = seeded(id).stream().map(DemoScenario::key).toList();
            assertThat(catalog.forIndustry(id).keySet()).as(id).hasSize(3).isSubsetOf(seededKeys);
        }
    }

    @Test
    void everyFixtureIsAnAiAnswerThatStillPassesTheSemanticChecksWithItsStoredReliability() {
        for (String id : packs.ids()) {
            PackRules pack = packs.require(id);
            for (DemoScenario scenario : seeded(id)) {
                RecordedTriage fixture = catalog.find(id, scenario.key()).orElse(null);
                if (fixture == null) continue;
                String label = id + "/" + scenario.key();
                assertThat(fixture.matches(scenario.subject(), scenario.message())).as(label + " scenario text").isTrue();
                RedactedTicket redacted = redactor.redact(scenario.subject(), scenario.message());

                ValidatedProviderResult validated = validator.validate(fixture.answer(), redacted, pack);

                assertThat(validated.reliabilitySignal()).as(label + " reliability").isEqualTo(fixture.reliabilitySignal());
                assertThat(fixture.answer().modelVersion()).as(label).isNotBlank();
                assertThat(fixture.answer().promptVersion()).as(label).isNotBlank();
                assertThat(fixture.capturedAt()).as(label).isNotNull();
            }
        }
    }

    @Test
    void theScorerGivesEachFixtureTheScoreTheLiveCaptureHadAndGivesItTwice() {
        int checked = 0;
        for (String id : packs.ids()) {
            PackRules pack = packs.require(id);
            for (DemoScenario scenario : seeded(id)) {
                RecordedTriage fixture = catalog.find(id, scenario.key()).orElse(null);
                if (fixture == null) continue;
                UUID ticketId = UUID.randomUUID();
                TriageDecision first = fixture.toDecision(ticketId, scenario.subject(), scenario.message(), pack, scorer);
                TriageDecision second = fixture.toDecision(ticketId, scenario.subject(), scenario.message(), pack, scorer);

                assertThat(first.priorityScore()).as(scenario.key()).isEqualTo(CAPTURED_SCORES.get(scenario.key()));
                assertThat(second.priorityScore()).isEqualTo(first.priorityScore());
                assertThat(second.appliedRules()).isEqualTo(first.appliedRules()).isNotEmpty();
                assertThat(first.decisionSource()).isEqualTo(DecisionSource.AI_VALIDATED);
                assertThat(first.fallbackReason()).isNull();
                checked++;
            }
        }
        assertThat(checked).isEqualTo(9);
    }

    @Test
    void aFixtureWhoseScenarioTextChangesLaterFailsTheGate() {
        PackRules pack = packs.require("saas-support");
        DemoScenario scenario = seeded("saas-support").stream().filter(item -> item.key().equals("saas-api-outage"))
                .findFirst().orElseThrow();
        RecordedTriage fixture = catalog.find("saas-support", "saas-api-outage").orElseThrow();
        String reworded = scenario.message().replace("returned 503", "answered with an error");

        assertThat(reworded).isNotEqualTo(scenario.message());
        assertThat(fixture.matches(scenario.subject(), reworded)).isFalse();
        assertThatThrownBy(() -> validator.validate(fixture.answer(), redactor.redact(scenario.subject(), reworded), pack))
                .isInstanceOf(SemanticValidationException.class);
        assertThat(fixture.matches(scenario.subject() + " ", scenario.message())).isFalse();
    }

    @Test
    void anIndustryWithoutFixturesAnswersEmptyAndAnUnknownScenarioIsNotFound() {
        assertThat(catalog.forIndustry("no-such-industry")).isEmpty();
        assertThat(catalog.find("ev-charging", "no-such-scenario")).isEmpty();
    }

    private static List<DemoScenario> seeded(String industry) {
        String path = industry.equals("ev-charging") ? "demo/scenarios-v1.json" : "packs/" + industry + "/scenarios.json";
        return ScenarioCatalog.fromClasspath(path).ordered().stream()
                .filter(scenario -> !scenario.key().equals("provider-retry-demo")).toList();
    }
}
