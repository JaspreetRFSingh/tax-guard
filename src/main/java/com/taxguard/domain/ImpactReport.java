package com.taxguard.domain;

import com.taxguard.domain.enums.RiskLevel;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Finance-readable report produced by ImpactQuantifier.
 *
 * Positive totalDelta  = new rule collects MORE tax  (risk: over-collection complaints, refund liability)
 * Negative totalDelta  = new rule collects LESS tax  (risk: under-remittance to tax authority → penalties)
 */
public record ImpactReport(
    String proposedRuleId,
    String simulationWindow,                          // e.g., "30 days"
    long   transactionsAnalyzed,
    long   transactionsAffected,
    BigDecimal totalDeltaInPeriod,                    // Net change over simulation window
    BigDecimal projectedAnnualDelta,                  // Extrapolated to 365 days
    Map<String, BigDecimal> deltaByJurisdiction,
    Map<String, BigDecimal> deltaByCategory,
    BigDecimal maxSingleTransactionDelta,
    RiskLevel riskLevel,
    String regulatoryNote                             // Auto-generated compliance note
) {}
