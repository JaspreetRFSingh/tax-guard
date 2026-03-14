package com.taxguard.impact;

import com.taxguard.domain.*;
import com.taxguard.domain.enums.RecommendedAction;
import com.taxguard.domain.enums.RiskLevel;
import com.taxguard.domain.enums.RulePriority;
import com.taxguard.domain.enums.RuleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImpactQuantifierTest {

    private ImpactQuantifier quantifier;

    @BeforeEach
    void setUp() {
        quantifier = new ImpactQuantifier();
    }

    private TaxRule rule(double rate) {
        return new TaxRule("r1", "IN-KA", "FOOD",
            BigDecimal.valueOf(rate),
            LocalDate.of(2025, 4, 1), null,
            RulePriority.FEDERAL, "IN-GST-2025.Q2",
            "CGST Amendment 15/2025", RuleStatus.DRAFT);
    }

    private SimulationReport report(long total, long divergent,
                                     double totalDelta, double divergenceRate,
                                     RecommendedAction action) {
        return new SimulationReport(
            total, divergent,
            BigDecimal.valueOf(totalDelta),
            Map.of("IN-KA", BigDecimal.valueOf(totalDelta * 0.4),
                   "IN-MH", BigDecimal.valueOf(totalDelta * 0.6)),
            Map.of("FOOD", BigDecimal.valueOf(totalDelta)),
            BigDecimal.valueOf(totalDelta / Math.max(divergent, 1)),
            divergenceRate,
            action
        );
    }

    // ── Annual projection ─────────────────────────────────────────────────────

    @Test
    @DisplayName("30-day delta extrapolated correctly to annual")
    void annualExtrapolation_correct() {
        SimulationReport sim = report(1_000_000, 900_000, 100_000.0, 90.0,
                                       RecommendedAction.BLOCK_DEPLOYMENT);

        ImpactReport impact = quantifier.quantify(sim, rule(0.12), 30);

        // 100_000 * (365/30) ≈ 1_216_666.67
        assertThat(impact.projectedAnnualDelta().doubleValue())
            .isCloseTo(100_000.0 * 365.0 / 30.0, org.assertj.core.api.Assertions.within(1.0));
    }

    @Test
    @DisplayName("7-day simulation window scales up correctly")
    void sevenDayWindow_scalesUp() {
        SimulationReport sim = report(500_000, 450_000, 50_000.0, 90.0,
                                       RecommendedAction.BLOCK_DEPLOYMENT);

        ImpactReport impact = quantifier.quantify(sim, rule(0.12), 7);

        // 50_000 * (365/7) ≈ 2_607_142.86
        assertThat(impact.projectedAnnualDelta().doubleValue())
            .isCloseTo(50_000.0 * 365.0 / 7.0, org.assertj.core.api.Assertions.within(1.0));
    }

    // ── Risk tier assignment ──────────────────────────────────────────────────

    @Test
    @DisplayName("Annual delta > $10M → CRITICAL")
    void annualDeltaOverTenMillion_critical() {
        // 30-day delta = $1M → annual ≈ $12.2M
        SimulationReport sim = report(10_000_000, 9_000_000, 1_000_000.0, 90.0,
                                       RecommendedAction.BLOCK_DEPLOYMENT);

        ImpactReport impact = quantifier.quantify(sim, rule(0.12), 30);

        assertThat(impact.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    @DisplayName("Annual delta $1M–$10M → HIGH")
    void annualDeltaOneMillion_high() {
        // 30-day delta ≈ $82K → annual ≈ $1M
        SimulationReport sim = report(1_000_000, 900_000, 82_000.0, 90.0,
                                       RecommendedAction.MANUAL_REVIEW);

        ImpactReport impact = quantifier.quantify(sim, rule(0.12), 30);

        assertThat(impact.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    @DisplayName("Annual delta < $100K → LOW")
    void annualDeltaSmall_low() {
        // 30-day delta = $5K → annual ≈ $60K
        SimulationReport sim = report(100_000, 50, 5_000.0, 0.05,
                                       RecommendedAction.SAFE_TO_DEPLOY);

        ImpactReport impact = quantifier.quantify(sim, rule(0.051), 30);

        assertThat(impact.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("Negative delta (rate decrease) still assigned correct risk tier")
    void negativeDelta_absoluteValueUsedForRiskTier() {
        // Rate cut — tax collection goes DOWN by $1M → still HIGH risk
        SimulationReport sim = report(1_000_000, 900_000, -82_000.0, 90.0,
                                       RecommendedAction.MANUAL_REVIEW);

        ImpactReport impact = quantifier.quantify(sim, rule(0.03), 30);

        // Risk is based on |delta|, not signed delta
        assertThat(impact.riskLevel()).isIn(RiskLevel.HIGH, RiskLevel.MEDIUM);
        assertThat(impact.totalDeltaInPeriod()).isNegative();
    }

    // ── Regulatory note content ───────────────────────────────────────────────

    @Test
    @DisplayName("CRITICAL risk → regulatory note demands CFO sign-off")
    void criticalRisk_noteContainsCfoSignOff() {
        SimulationReport sim = report(10_000_000, 9_000_000, 1_000_000.0, 90.0,
                                       RecommendedAction.BLOCK_DEPLOYMENT);

        ImpactReport impact = quantifier.quantify(sim, rule(0.12), 30);

        assertThat(impact.regulatoryNote())
            .containsIgnoringCase("CFO")
            .containsIgnoringCase("Legal");
    }

    @Test
    @DisplayName("LOW risk → regulatory note says Finance review recommended")
    void lowRisk_noteContainsFinanceReview() {
        SimulationReport sim = report(100_000, 50, 5_000.0, 0.05,
                                       RecommendedAction.SAFE_TO_DEPLOY);

        ImpactReport impact = quantifier.quantify(sim, rule(0.051), 30);

        assertThat(impact.regulatoryNote())
            .containsIgnoringCase("Finance")
            .doesNotContainIgnoringCase("CFO");
    }

    @Test
    @DisplayName("Regulatory note always includes rule ID, jurisdiction, and regulation ref")
    void regulatoryNote_alwaysContainsAuditFields() {
        SimulationReport sim = report(1_000_000, 500_000, 50_000.0, 50.0,
                                       RecommendedAction.MANUAL_REVIEW);

        ImpactReport impact = quantifier.quantify(sim, rule(0.08), 30);

        assertThat(impact.regulatoryNote())
            .contains("r1")                     // ruleId
            .contains("IN-KA")                  // jurisdiction
            .contains("CGST Amendment 15/2025"); // sourceRegulation
    }
}
