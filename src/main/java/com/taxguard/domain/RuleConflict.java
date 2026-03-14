package com.taxguard.domain;

import java.math.BigDecimal;

/**
 * Represents a detected conflict between two tax rules.
 * Produced by RuleConflictDetector and surfaced in the validation pipeline.
 */
public record RuleConflict(
    String proposedRuleId,
    String existingRuleId,
    String jurisdiction,
    String productCategory,
    BigDecimal proposedRate,
    BigDecimal existingRate,
    String conflictPeriod,       // Human-readable description of the overlap window
    String resolutionAdvice      // Auto-generated suggestion to resolve the conflict
) {}
