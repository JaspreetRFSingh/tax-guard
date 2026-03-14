package com.taxguard.domain.enums;

/**
 * Financial risk tier based on projected annual tax delta.
 *
 * LOW      : |delta| <= $100K   — Finance team review
 * MEDIUM   : |delta| <= $1M     — Finance team approval
 * HIGH     : |delta| <= $10M    — VP Finance approval
 * CRITICAL : |delta| >  $10M    — CFO + Legal sign-off
 */
public enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }
