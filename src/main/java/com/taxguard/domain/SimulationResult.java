package com.taxguard.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Result for a single historical transaction during shadow simulation.
 * Captures the difference between what was calculated (baseline) vs
 * what would have been calculated under the proposed rule (proposed).
 */
public record SimulationResult(
    String transactionId,
    String jurisdiction,
    String productCategory,
    BigDecimal transactionAmount,
    BigDecimal baselineTax,   // Tax under current ACTIVE rules
    BigDecimal proposedTax,   // Tax under PROPOSED rule set
    BigDecimal delta,         // proposedTax - baselineTax
    boolean isDivergent       // true if delta != 0
) {}
