package com.taxguard.impact;

import com.taxguard.domain.*;
import com.taxguard.domain.enums.RiskLevel;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Translates a technical SimulationReport into a finance-readable ImpactReport.
 *
 * Separation of concerns:
 *   SimulationEngine  = "which transactions changed and by how much?"  (engineering)
 *   ImpactQuantifier  = "what does that mean financially and legally?" (finance/compliance)
 *
 * The extrapolation from simulation window → annual projection is intentionally
 * transparent: the simulationWindow is included in the report so the finance team
 * can apply their own seasonality adjustments (e.g., Diwali season skews India numbers).
 */
@Service
public class ImpactQuantifier {

    // Risk thresholds in absolute USD (configurable per jurisdiction in production)
    private static final BigDecimal CRITICAL_THRESHOLD = new BigDecimal("10000000"); // $10M
    private static final BigDecimal HIGH_THRESHOLD     = new BigDecimal("1000000");  // $1M
    private static final BigDecimal MEDIUM_THRESHOLD   = new BigDecimal("100000");   // $100K

    /**
     * Produce an ImpactReport from simulation results.
     *
     * @param simulation          Output from ShadowSimulationEngine
     * @param proposedRule        The rule being evaluated
     * @param simulationWindowDays Number of historical days that were replayed
     */
    public ImpactReport quantify(SimulationReport simulation,
                                  TaxRule proposedRule,
                                  int simulationWindowDays) {

        BigDecimal totalDelta = simulation.totalDelta();

        // Extrapolate: delta over N days → annual projection
        // NOTE: This assumes uniform transaction volume — fine for a first pass.
        // A production system would weight by day-of-week and seasonal indices.
        BigDecimal annualMultiplier = new BigDecimal(365)
            .divide(new BigDecimal(simulationWindowDays), 6, RoundingMode.HALF_UP);

        BigDecimal projectedAnnualDelta = totalDelta
            .multiply(annualMultiplier)
            .setScale(2, RoundingMode.HALF_UP);

        RiskLevel risk = computeRiskLevel(projectedAnnualDelta);

        return new ImpactReport(
            proposedRule.getRuleId(),
            simulationWindowDays + " days",
            simulation.totalTransactions(),
            simulation.divergentCount(),
            totalDelta.setScale(2, RoundingMode.HALF_UP),
            projectedAnnualDelta,
            simulation.deltaByJurisdiction(),
            simulation.deltaByCategory(),
            simulation.maxSingleTransactionDelta(),
            risk,
            buildRegulatoryNote(proposedRule, risk, projectedAnnualDelta,
                                simulation.divergenceRate())
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RiskLevel computeRiskLevel(BigDecimal annualDelta) {
        BigDecimal abs = annualDelta.abs();
        if (abs.compareTo(CRITICAL_THRESHOLD) >= 0) return RiskLevel.CRITICAL;
        if (abs.compareTo(HIGH_THRESHOLD)     >= 0) return RiskLevel.HIGH;
        if (abs.compareTo(MEDIUM_THRESHOLD)   >= 0) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    /**
     * Auto-generates a plain-English compliance note for the finance/legal team.
     * Avoids engineering jargon — this is read by non-engineers.
     */
    private String buildRegulatoryNote(TaxRule rule, RiskLevel risk,
                                        BigDecimal annualDelta, double divergenceRate) {
        String direction = annualDelta.compareTo(BigDecimal.ZERO) > 0
            ? String.format("INCREASE tax collected by approximately %s per year",
                            formatCurrency(annualDelta))
            : String.format("DECREASE tax collected by approximately %s per year",
                            formatCurrency(annualDelta.abs()));

        String approval = switch (risk) {
            case CRITICAL -> "REQUIRES CFO + Legal sign-off before deployment.";
            case HIGH     -> "Requires VP Finance approval before deployment.";
            case MEDIUM   -> "Requires Finance team approval before deployment.";
            case LOW      -> "Finance team review recommended (informational).";
        };

        return String.format(
            "Rule '%s' (%s) would %s, affecting %.1f%% of transactions in %s. " +
            "Regulation basis: %s. Risk level: %s. %s",
            rule.getRuleId(), rule.getRuleVersion(),
            direction, divergenceRate, rule.getJurisdiction(),
            rule.getSourceRegulation() != null ? rule.getSourceRegulation() : "not specified",
            risk, approval
        );
    }

    private String formatCurrency(BigDecimal amount) {
        return "$" + String.format("%,.2f", amount);
    }
}
