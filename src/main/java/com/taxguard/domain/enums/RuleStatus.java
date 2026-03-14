package com.taxguard.domain.enums;

public enum RuleStatus {
    DRAFT,           // Just created, not yet reviewed
    PENDING_REVIEW,  // Passed conflict check, awaiting approval
    ACTIVE,          // Live in production
    SUPERSEDED       // Replaced by a newer rule
}
