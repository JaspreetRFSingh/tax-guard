package com.taxguard.domain.enums;

/**
 * Priority determines which rule wins when two rules overlap.
 * Higher ordinal = higher priority (FEDERAL beats DEFAULT).
 *
 * Example: A federal GST exemption for medicine overrides a state surcharge.
 */
public enum RulePriority {
    DEFAULT,   // Catch-all fallback
    CITY,      // Municipal/city-level rule
    STATE,     // State/province-level rule
    FEDERAL    // National/federal rule — highest precedence
}
