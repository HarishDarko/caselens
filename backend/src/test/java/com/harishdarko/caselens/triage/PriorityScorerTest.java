package com.harishdarko.caselens.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PriorityScorerTest {
    private final PriorityScorer scorer = new PriorityScorer();

    @ParameterizedTest
    @MethodSource("baseScores")
    void appliesUrgencyAndSlaRisk(Urgency urgency, SlaRisk risk, int expected) {
        PriorityResult result = scorer.score(new PriorityContext(
                urgency, risk, Category.GENERAL, "ordinary support request", 0));

        assertThat(result.score()).isEqualTo(expected);
    }

    static Stream<Arguments> baseScores() {
        return Stream.of(
                Arguments.of(Urgency.LOW, SlaRisk.LOW, 10),
                Arguments.of(Urgency.MEDIUM, SlaRisk.MEDIUM, 35),
                Arguments.of(Urgency.HIGH, SlaRisk.HIGH, 60),
                Arguments.of(Urgency.CRITICAL, SlaRisk.HIGH, 75));
    }

    @ParameterizedTest
    @MethodSource("indicatorRules")
    void appliesEachBusinessImpactRule(String text, int repeatCount, int expected, String ruleCode) {
        PriorityResult result = scorer.score(new PriorityContext(
                Urgency.LOW, SlaRisk.LOW, Category.CHARGING_SESSION, text, repeatCount));

        assertThat(result.score()).isEqualTo(expected);
        assertThat(result.appliedRules()).extracting(RuleApplication::code).contains(ruleCode);
    }

    static Stream<Arguments> indicatorRules() {
        return Stream.of(
                Arguments.of("payment captured but no session", 0, 30, "PAYMENT_IMPACT"),
                Arguments.of("charging session blocked", 0, 20, "SERVICE_BLOCKED"),
                Arguments.of("site-wide outage affecting multiple users", 0, 25, "BROAD_IMPACT"),
                Arguments.of("ordinary issue", 2, 20, "REPEAT_CONTACT"));
    }

    @Test
    void capsSevereCombinedImpactAtOneHundred() {
        PriorityResult result = scorer.score(new PriorityContext(
                Urgency.CRITICAL,
                SlaRisk.HIGH,
                Category.REFUND,
                "site-wide safety outage affecting multiple users; duplicate charge and refund; session blocked",
                3));

        assertThat(result.score()).isEqualTo(100);
    }

    @Test
    void reducesPositiveGeneralRequestsWithoutGoingBelowZero() {
        PriorityResult result = scorer.score(new PriorityContext(
                Urgency.LOW, SlaRisk.LOW, Category.GENERAL,
                "Thanks, everything works. I only have a feature request.", 0));

        assertThat(result.score()).isZero();
        assertThat(result.appliedRules()).extracting(RuleApplication::code).contains("POSITIVE_GENERAL_REQUEST");
    }
}
