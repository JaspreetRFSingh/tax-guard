package com.taxguard.domain;

import com.taxguard.domain.enums.RecommendedAction;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Aggregate report produced by ShadowSimulationEngine after replaying
 * historical transactions through old vs new rule sets.
 */
public record SimulationReport(
        long totalTransactions,
        long divergentCount,
        BigDecimal totalDelta,                       // Net change in tax collected
        Map<String, BigDecimal> deltaByJurisdiction, // Per-region breakdown
        Map<String, BigDecimal> deltaByCategory,     // Per-product-category breakdown
        BigDecimal maxSingleTransactionDelta,        // Worst single-transaction impact
        double divergenceRate,                       // % of transactions affected
        RecommendedAction recommendedAction          // SAFE_TO_DEPLOY / MANUAL_REVIEW / BLOCK
) {}
