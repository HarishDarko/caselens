package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.harishdarko.caselens.demo.DemoScenario;
import com.harishdarko.caselens.demo.ScenarioCatalog;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The demo tickets of each non-EV pack fit the pack, and the offline rules agreement on them is pinned. The rules
 * and these tickets were written together, so the agreement is a consistency check of the content, not an
 * accuracy claim. EV agreement (4/8, 4/8, 2/8) is pinned by EvRulesCharacterizationTest.
 */
class DemoPackScenariosTest {
    /** Floors: the agreement measured when the packs were written (category, urgency, both, out of n). */
    private static final Map<String, List<Integer>> FLOORS = Map.of(
            "saas-support", List.of(8, 8, 8, 8),
            "e-commerce", List.of(8, 8, 8, 8));

    private final RulesFallbackProvider rules = new RulesFallbackProvider();
    private final TicketRedactor redactor = new TicketRedactor();

    @Test
    void everyNonEvPackHasDemoTicketsThatFitThePack() {
        for (String id : FLOORS.keySet()) {
            PackRules pack = PackCatalog.builtIn().require(id);
            List<DemoScenario> scenarios = scenarios(id);

            assertThat(scenarios).as(id).hasSizeBetween(6, 8);
            assertThat(scenarios).as(id).allSatisfy(scenario -> {
                assertThat(pack.categories()).contains(Category.of(scenario.expectedCategory()));
                assertThat(Urgency.valueOf(scenario.expectedUrgency())).isNotNull();
                assertThat(scenario.key()).doesNotContain("charger");
            });
            assertThat(scenarios.stream().map(scenario -> scenario.expectedUrgency())).as(id).contains("CRITICAL");
        }
    }

    @Test
    void rulesAgreementOnEachPacksDemoTicketsMeetsItsFloor() {
        for (Map.Entry<String, List<Integer>> floor : FLOORS.entrySet()) {
            PackRules pack = PackCatalog.builtIn().require(floor.getKey());
            int evaluated = 0, category = 0, urgency = 0, both = 0;
            for (DemoScenario scenario : scenarios(floor.getKey())) {
                TriageRequest request = new TriageRequest(UUID.randomUUID(), scenario.subject(), scenario.message(),
                        scenario.channel(), 0, pack);
                ProviderTriageResult result = rules.analyze(request, redactor.redact(scenario.subject(), scenario.message())).result();
                boolean c = result.category().name().equals(scenario.expectedCategory());
                boolean u = result.urgency().name().equals(scenario.expectedUrgency());
                evaluated++;
                if (c) category++;
                if (u) urgency++;
                if (c && u) both++;
                System.out.println("PACK-RULES " + floor.getKey() + " " + scenario.key() + " expected=" + scenario.expectedCategory()
                        + "/" + scenario.expectedUrgency() + " rules=" + result.category() + "/" + result.urgency()
                        + " policies=" + result.policyIds());
            }
            System.out.println("PACK-RULES total " + floor.getKey() + ": evaluated=" + evaluated + " categoryMatches=" + category
                    + " urgencyMatches=" + urgency + " fullMatches=" + both);
            assertThat(evaluated).isEqualTo(floor.getValue().get(3));
            assertThat(category).as(floor.getKey() + " category").isGreaterThanOrEqualTo(floor.getValue().get(0));
            assertThat(urgency).as(floor.getKey() + " urgency").isGreaterThanOrEqualTo(floor.getValue().get(1));
            assertThat(both).as(floor.getKey() + " both").isGreaterThanOrEqualTo(floor.getValue().get(2));
        }
    }

    private List<DemoScenario> scenarios(String id) {
        return ScenarioCatalog.fromClasspath("packs/" + id + "/scenarios.json").ordered();
    }
}
