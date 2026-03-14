package com.taxguard.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Kafka event emitted after each tax calculation.
 * Consumed by RateAnomalyDetector for real-time monitoring.
 */
public record TaxCalculationEvent(
    String transactionId,
    String jurisdiction,
    String productCategory,
    BigDecimal baseAmount,
    BigDecimal taxCollected,
    String appliedRuleVersion,
    Instant calculatedAt
) {}
