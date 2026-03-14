package com.taxguard.domain;

public enum DeploymentDecision {
    BLOCKED_CONFLICTS,      // Hard block — conflicts must be resolved first
    REQUIRES_CFO_APPROVAL,  // CRITICAL risk — needs CFO + Legal
    REQUIRES_FINANCE_APPROVAL, // HIGH/MEDIUM risk — needs Finance team
    SAFE_TO_DEPLOY          // Low risk, no conflicts, low divergence
}
