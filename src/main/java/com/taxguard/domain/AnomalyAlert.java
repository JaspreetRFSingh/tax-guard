package com.taxguard.domain;

import com.taxguard.domain.enums.Severity;

import java.time.Instant;

/**
 * Fired by RateAnomalyDetector when the effective tax rate for a
 * (jurisdiction × productCategory) segment deviates beyond Z_THRESHOLD
 * standard deviations from its rolling mean.
 */
public record AnomalyAlert(
    String jurisdiction,
    String productCategory,
    double observedRate,
    double expectedRate,         // Rolling mean
    double zScore,               // How many std devs away from mean
    Severity severity,
    String message,
    String appliedRuleVersion,   // Which rule was active when anomaly occurred
    Instant detectedAt
) {}
