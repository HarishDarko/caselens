package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PackRulesTest {
    private final PackRules pack = PackRules.evCharging();

    @Test
    void everyPolicyTheEvPackCanReturnExistsInThePolicyCatalog() {
        PolicyCatalog catalog = PolicyCatalog.defaultCatalog();
        String everyTerm = "payment charged no session duplicate site-wide connector fault";
        for (Category category : pack.categories()) {
            assertThat(pack.policyIds(everyTerm, category)).allSatisfy(id -> assertThat(catalog.contains(id)).isTrue());
        }
        assertThat(pack.policyIds(everyTerm, Category.of("CHARGER_HARDWARE")))
                .containsExactly("PAYMENT_CAPTURE_NO_SESSION", "DUPLICATE_CHARGE_REVIEW", "CHARGER_SITE_OUTAGE", "CONNECTOR_FAULT");
    }

    @Test
    void theFirstMatchingRuleInFileOrderWins() {
        assertThat(pack.category("refund for a charger that is offline")).contains(Category.of("BILLING"));
        assertThat(pack.category("charger is offline")).contains(Category.of("CONNECTIVITY"));
        assertThat(pack.category("nothing relevant")).isEmpty();
    }

    @Test
    void everyCategoryAndUrgencyHasActionsAndEvidenceTerms() {
        for (Category category : pack.categories()) {
            assertThat(pack.actions(category)).isNotEmpty();
            assertThat(pack.evidenceSupports(category, "")).isFalse();
        }
        for (Urgency urgency : Urgency.values()) assertThat(pack.evidenceSupports(urgency, "")).isFalse();
    }

    @Test
    void aMissingPackFileFailsLoudly() {
        assertThatThrownBy(() -> PackRules.load("/packs/does-not-exist/pack.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Pack file is missing");
    }
}
