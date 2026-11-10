package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PackCatalogTest {
    @Test
    void theBuiltInCatalogLoadsEveryListedPackAndDefaultsToEvCharging() {
        PackCatalog catalog = PackCatalog.builtIn();

        assertThat(catalog.ids()).contains(PackCatalog.DEFAULT_PACK);
        assertThat(catalog.defaultPack().id()).isEqualTo("ev-charging");
        assertThat(catalog.defaultPack().name()).isEqualTo("EV charging");
        assertThat(catalog.require("ev-charging")).isSameAs(catalog.defaultPack());
    }

    @Test
    void anUnknownPackIdIsRejected() {
        assertThatThrownBy(() -> PackCatalog.builtIn().require("not-a-pack"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown industry pack");
    }

    @Test
    void aCatalogWithoutTheDefaultPackIsRejected() {
        assertThatThrownBy(() -> new PackCatalog(Map.of("test-saas", TestPacks.saas())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("default industry pack is missing");
    }

    @Test
    void theEvPackPoliciesMatchTheVersionedPolicyFile() {
        PolicyCatalog fromPack = PackCatalog.builtIn().defaultPack().policies();
        PolicyCatalog fromFile = PolicyCatalog.defaultCatalog();

        assertThat(fromPack.version()).isEqualTo(fromFile.version());
        assertThat(fromPack.ids()).containsExactlyInAnyOrderElementsOf(fromFile.ids());
        for (String id : fromFile.ids()) assertThat(fromPack.guidance(id)).isEqualTo(fromFile.guidance(id));
    }

    @Test
    void aValidNonEvPackLoadsWithItsOwnCategoriesAndPolicies() {
        PackRules pack = TestPacks.saas();

        assertThat(pack.id()).isEqualTo("test-saas");
        assertThat(pack.categories()).containsExactly(Category.of("LOGIN"), Category.of("INVOICE"), Category.of("GENERAL"));
        assertThat(pack.generalCategory()).isEqualTo(Category.of("GENERAL"));
        assertThat(pack.policies().ids()).containsExactlyInAnyOrder("ACCOUNT_LOCKOUT", "INVOICE_DISPUTE");
        assertThatThrownBy(() -> pack.requireCategory("BILLING")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPackWhoseIdDoesNotMatchItsFolderIsRejected() {
        assertThatThrownBy(() -> PackRules.parse(TestPacks.SAAS_JSON, "other-folder"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not match its folder");
    }

    @Test
    void aMatchTermWithCapitalLettersIsRejected() {
        assertBroken(TestPacks.SAAS_JSON.replace("\"locked out\", \"login\"", "\"locked out\", \"Login\""), "must be lower case: Login");
    }

    @Test
    void aBlankMatchTermIsRejected() {
        assertBroken(TestPacks.SAAS_JSON.replace("\"terms\": [\"thanks\"]", "\"terms\": [\" \"]"), "must not be blank");
    }

    @Test
    void aRuleThatCitesAPolicyThePackDoesNotHaveIsRejected() {
        assertBroken(TestPacks.SAAS_JSON.replace("{ \"id\": \"INVOICE_DISPUTE\", \"allOf\"", "{ \"id\": \"PAYMENT_CAPTURE_NO_SESSION\", \"allOf\""),
                "unknown policy: PAYMENT_CAPTURE_NO_SESSION");
    }

    @Test
    void aRuleWithACategoryOutsideThePackIsRejected() {
        assertThatThrownBy(() -> PackRules.parse(TestPacks.SAAS_JSON.replace("{ \"value\": \"INVOICE\", \"terms\"",
                "{ \"value\": \"BILLING\", \"terms\""), "test-saas"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Category is not part of this pack");
    }

    @Test
    void aCategoryWithoutActionsIsRejected() {
        assertBroken(TestPacks.SAAS_JSON.replace("\"INVOICE\": [\"Check the invoice lines\"],", ""), "missing field: INVOICE");
    }

    @Test
    void aMissingPriorityIndicatorIsRejected() {
        assertBroken(TestPacks.SAAS_JSON.replace("\"BROAD_IMPACT\"", "\"OTHER_IMPACT\""), "missing field: BROAD_IMPACT");
    }

    @Test
    void aMissingPackFileFailsWithAClearMessage() {
        assertThatThrownBy(() -> PackRules.load("does-not-exist"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Pack file is missing: /packs/does-not-exist/pack.json");
    }

    private void assertBroken(String json, String message) {
        assertThat(json).isNotEqualTo(TestPacks.SAAS_JSON);
        assertThatThrownBy(() -> PackRules.parse(json, "test-saas"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(message);
    }
}
