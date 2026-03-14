package com.taxguard.simulation;

import com.taxguard.domain.*;
import com.taxguard.domain.enums.RecommendedAction;
import com.taxguard.domain.enums.RulePriority;
import com.taxguard.domain.enums.RuleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowSimulationEngineTest {

    private ShadowSimulationEngine engine;
    private RuleBasedTaxCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new RuleBasedTaxCalculator();
        engine     = new ShadowSimulationEngine(calculator);
    }

    private TaxRule rule(String id, String jur, String cat,
                          String from, String to, double rate) {
        return new TaxRule(id, jur, cat,
            BigDecimal.valueOf(rate),
            LocalDate.parse(from),
            to != null ? LocalDate.parse(to) : null,
            RulePriority.FEDERAL, "v1", null, RuleStatus.ACTIVE);
    }

    private TaxTransaction tx(String jur, String cat, double amount) {
        return new TaxTransaction(
            UUID.randomUUID().toString(), jur, cat,
            BigDecimal.valueOf(amount),
            BigDecimal.valueOf(amount * 0.05), // assume 5% was charged
            "old-rule-v1",
            Instant.now()
        );
    }

    @Test
    @DisplayName("No rule change → zero divergence, SAFE_TO_DEPLOY")
    void identicalRuleSets_zeroDivergence() {
        List<TaxRule> rules = List.of(
            rule("r1", "IN-KA", "FOOD", "2024-01-01", null, 0.05));
        List<TaxTransaction> history = List.of(
            tx("IN-KA", "FOOD", 1000),
            tx("IN-KA", "FOOD", 2000));

        SimulationReport report = engine.simulate(history, rules, rules);

        assertThat(report.divergentCount()).isZero();
        assertThat(report.totalDelta()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.divergenceRate()).isZero();
        assertThat(report.recommendedAction()).isEqualTo(RecommendedAction.SAFE_TO_DEPLOY);
    }

    @Test
    @DisplayName("Rate change from 5% to 12% → all food transactions diverge")
    void rateChange_allTransactionsDiverge() {
        List<TaxRule> currentRules = List.of(
            rule("r1", "IN-KA", "FOOD", "2024-01-01", null, 0.05));
        List<TaxRule> proposedRules = List.of(
            rule("r2", "IN-KA", "FOOD", "2024-01-01", null, 0.12));

        List<TaxTransaction> history = List.of(
            tx("IN-KA", "FOOD", 1000),
            tx("IN-KA", "FOOD", 1000));

        SimulationReport report = engine.simulate(history, currentRules, proposedRules);

        // Both transactions diverge: 12% - 5% = 7% on 1000 = 70 each, total delta = 140
        assertThat(report.divergentCount()).isEqualTo(2);
        assertThat(report.totalDelta()).isEqualByComparingTo("140.0000");
        assertThat(report.divergenceRate()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Only matching jurisdiction/category transactions diverge")
    void onlyMatchingTransactionsDiverge() {
        List<TaxRule> currentRules = List.of(
            rule("r1", "IN-KA", "FOOD",  "2024-01-01", null, 0.05),
            rule("r2", "IN-KA", "RIDES", "2024-01-01", null, 0.05));
        List<TaxRule> proposedRules = List.of(
            rule("r1", "IN-KA", "FOOD",  "2024-01-01", null, 0.12), // changed
            rule("r2", "IN-KA", "RIDES", "2024-01-01", null, 0.05)); // unchanged

        List<TaxTransaction> history = List.of(
            tx("IN-KA", "FOOD",  1000),  // diverges
            tx("IN-KA", "RIDES", 1000)); // no divergence

        SimulationReport report = engine.simulate(history, currentRules, proposedRules);

        assertThat(report.divergentCount()).isEqualTo(1);
        assertThat(report.divergenceRate()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("High divergence rate → BLOCK_DEPLOYMENT recommendation")
    void highDivergenceRate_blockRecommended() {
        List<TaxRule> current  = List.of(rule("r1", "IN-KA", "FOOD", "2024-01-01", null, 0.05));
        List<TaxRule> proposed = List.of(rule("r2", "IN-KA", "FOOD", "2024-01-01", null, 0.28));

        // 20 transactions all diverge → 100% divergence rate
        List<TaxTransaction> history = java.util.stream.IntStream.range(0, 20)
            .mapToObj(i -> tx("IN-KA", "FOOD", 500))
            .collect(java.util.stream.Collectors.toList());

        SimulationReport report = engine.simulate(history, current, proposed);

        assertThat(report.recommendedAction()).isEqualTo(RecommendedAction.BLOCK_DEPLOYMENT);
    }
}
