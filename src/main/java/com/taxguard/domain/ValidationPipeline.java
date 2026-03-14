package com.taxguard.domain;

import com.taxguard.domain.enums.DeploymentDecision;

import java.util.List;

/**
 * Top-level response from POST /api/v1/taxguard/rules/validate.
 * Bundles the outputs of all four engines into one response.
 */
public record ValidationPipeline(
    String proposedRuleId,
    List<RuleConflict> conflicts,
    SimulationReport simulation,  // null if blocked by conflicts
    ImpactReport impact,          // null if blocked by conflicts
    DeploymentDecision decision,
    String blockedReason          // non-null only when decision = BLOCKED_*
) {
    /** Factory for a blocked-by-conflicts response */
    public static ValidationPipeline blocked(String ruleId, List<RuleConflict> conflicts) {
        String reason = String.format(
            "%d conflict(s) detected. Resolve before simulation can run.", conflicts.size());
        return new ValidationPipeline(ruleId, conflicts, null, null,
                                      DeploymentDecision.BLOCKED_CONFLICTS, reason);
    }
}
