package com.taxguard.service;

import com.taxguard.conflict.RuleConflictDetector;
import com.taxguard.domain.*;
import com.taxguard.domain.enums.DeploymentDecision;
import com.taxguard.domain.enums.RecommendedAction;
import com.taxguard.impact.ImpactQuantifier;
import com.taxguard.repository.TaxRuleRepository;
import com.taxguard.repository.TaxTransactionRepository;
import com.taxguard.simulation.ShadowSimulationEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates the full pre-deployment validation pipeline.
 *
 * Pipeline stages (each can gate/block the next):
 *
 *   [1] CONFLICT CHECK  → RuleConflictDetector   (fast, O(N log N))
 *       If conflicts exist → return BLOCKED immediately, skip simulation
 *
 *   [2] SHADOW SIM      → ShadowSimulationEngine  (slow, parallel)
 *       If divergence > 5% → recommend BLOCK_DEPLOYMENT
 *
 *   [3] IMPACT REPORT   → ImpactQuantifier        (fast, pure math)
 *       Determines risk tier and required approval level
 *
 *   [4] DEPLOYMENT DECISION  → derived from simulation + impact
 */
@Service
public class ValidationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ValidationOrchestrator.class);
    private static final int SIMULATION_WINDOW_DAYS = 30;

    private final RuleConflictDetector conflictDetector;
    private final ShadowSimulationEngine simulationEngine;
    private final ImpactQuantifier impactQuantifier;
    private final TaxRuleRepository ruleRepository;
    private final TaxTransactionRepository txRepository;
    private final Timer validationTimer;

    public ValidationOrchestrator(RuleConflictDetector conflictDetector,
                                   ShadowSimulationEngine simulationEngine,
                                   ImpactQuantifier impactQuantifier,
                                   TaxRuleRepository ruleRepository,
                                   TaxTransactionRepository txRepository,
                                   MeterRegistry meterRegistry) {
        this.conflictDetector  = conflictDetector;
        this.simulationEngine  = simulationEngine;
        this.impactQuantifier  = impactQuantifier;
        this.ruleRepository    = ruleRepository;
        this.txRepository      = txRepository;
        this.validationTimer   = meterRegistry.timer("taxguard.validation.duration");
    }

    public ValidationPipeline validate(TaxRule proposedRule) {
        return validationTimer.record(() -> doValidate(proposedRule));
    }

    private ValidationPipeline doValidate(TaxRule proposedRule) {
        log.info("[TaxGuard] Starting validation for rule: {}", proposedRule.getRuleId());

        List<TaxRule> activeRules = ruleRepository.findAllActive();

        // ── Stage 1: Conflict Detection (fast gate) ──────────────────────────
        List<RuleConflict> conflicts =
            conflictDetector.detectConflicts(proposedRule, activeRules);

        if (!conflicts.isEmpty()) {
            log.warn("[TaxGuard] BLOCKED — {} conflict(s) for rule {}",
                     conflicts.size(), proposedRule.getRuleId());
            return ValidationPipeline.blocked(proposedRule.getRuleId(), conflicts);
        }

        // ── Stage 2: Shadow Simulation (slow, parallel) ───────────────────────
        log.info("[TaxGuard] No conflicts. Running shadow simulation (last {} days)...",
                 SIMULATION_WINDOW_DAYS);

        List<TaxTransaction> history =
            txRepository.findLastNDays(SIMULATION_WINDOW_DAYS,
                                       proposedRule.getJurisdiction(),
                                       proposedRule.getProductCategory());

        // Build proposed rule set: replace/add the proposed rule into active rules
        List<TaxRule> proposedRuleSet = buildProposedRuleSet(activeRules, proposedRule);

        SimulationReport simulation =
            simulationEngine.simulate(history, activeRules, proposedRuleSet);

        // ── Stage 3: Impact Quantification ────────────────────────────────────
        ImpactReport impact =
            impactQuantifier.quantify(simulation, proposedRule, SIMULATION_WINDOW_DAYS);

        // ── Stage 4: Deployment Decision ──────────────────────────────────────
        DeploymentDecision decision = resolveDecision(simulation, impact);

        log.info("[TaxGuard] Validation complete for {}. Decision: {} | Risk: {}",
                 proposedRule.getRuleId(), decision, impact.riskLevel());

        return new ValidationPipeline(
            proposedRule.getRuleId(),
            conflicts,
            simulation,
            impact,
            decision,
            null
        );
    }

    private DeploymentDecision resolveDecision(SimulationReport sim, ImpactReport impact) {
        if (sim.recommendedAction() == RecommendedAction.BLOCK_DEPLOYMENT) {
            return DeploymentDecision.BLOCKED_CONFLICTS;
        }
        return switch (impact.riskLevel()) {
            case CRITICAL -> DeploymentDecision.REQUIRES_CFO_APPROVAL;
            case HIGH     -> DeploymentDecision.REQUIRES_FINANCE_APPROVAL;
            case MEDIUM   -> DeploymentDecision.REQUIRES_FINANCE_APPROVAL;
            case LOW      -> sim.recommendedAction() == RecommendedAction.SAFE_TO_DEPLOY
                             ? DeploymentDecision.SAFE_TO_DEPLOY
                             : DeploymentDecision.REQUIRES_FINANCE_APPROVAL;
        };
    }

    /**
     * Merges the proposed rule into the active rule set.
     * The proposed rule supersedes any existing rule for the same bucket.
     */
    private List<TaxRule> buildProposedRuleSet(List<TaxRule> activeRules, TaxRule proposed) {
        String bucket = proposed.getJurisdiction() + ":" + proposed.getProductCategory();
        List<TaxRule> result = new java.util.ArrayList<>(activeRules);
        result.removeIf(r ->
            (r.getJurisdiction() + ":" + r.getProductCategory()).equals(bucket));
        result.add(proposed);
        return result;
    }
}
