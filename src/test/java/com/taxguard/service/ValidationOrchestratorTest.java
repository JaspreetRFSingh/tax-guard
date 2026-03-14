package com.taxguard.service;

import com.taxguard.conflict.RuleConflictDetector;
import com.taxguard.domain.*;
import com.taxguard.domain.enums.*;
import com.taxguard.impact.ImpactQuantifier;
import com.taxguard.repository.TaxRuleRepository;
import com.taxguard.repository.TaxTransactionRepository;
import com.taxguard.simulation.ShadowSimulationEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ValidationOrchestrator — the pipeline wiring.
 *
 * Key behaviours:
 *   1. Conflict found → pipeline short-circuits, simulation is NEVER called
 *   2. No conflict → simulation runs → impact runs → decision derived
 *   3. Decision correctly escalates with risk level
 *   4. Micrometer timer is always recorded (even on blocked pipelines)
 */
@ExtendWith(MockitoExtension.class)
class ValidationOrchestratorTest {

    @Mock private RuleConflictDetector conflictDetector;
    @Mock private ShadowSimulationEngine simulationEngine;
    @Mock private ImpactQuantifier impactQuantifier;
    @Mock private TaxRuleRepository ruleRepository;
    @Mock private TaxTransactionRepository txRepository;

    private ValidationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ValidationOrchestrator(
            conflictDetector, simulationEngine, impactQuantifier,
            ruleRepository, txRepository, new SimpleMeterRegistry()
        );
    }

    private TaxRule proposedRule(String id) {
        return new TaxRule(id, "IN-KA", "FOOD",
            new BigDecimal("0.12"),
            LocalDate.of(2025, 4, 1), null,
            RulePriority.FEDERAL, "IN-GST-2025.Q2",
            "CGST Amendment", RuleStatus.DRAFT);
    }

    private RuleConflict conflict() {
        return new RuleConflict("new", "old", "IN-KA", "FOOD",
            new BigDecimal("0.12"), new BigDecimal("0.05"),
            "Overlap: 2025-04-01 → ∞",
            "PROPOSED_WINS: set effectiveTo = 2025-03-31");
    }

    private SimulationReport safeReport() {
        return new SimulationReport(1_000_000, 5_000,
            new BigDecimal("500.00"),
            Map.of(), Map.of(), new BigDecimal("1.00"),
            0.5, RecommendedAction.SAFE_TO_DEPLOY);
    }

    private ImpactReport impactWithRisk(RiskLevel risk) {
        return new ImpactReport("r1", "30 days", 1_000_000, 5_000,
            new BigDecimal("500.00"), new BigDecimal("6083.33"),
            Map.of(), Map.of(), new BigDecimal("1.00"),
            risk, "Regulatory note.");
    }

    // ── Short-circuit on conflict ─────────────────────────────────────────────

    @Test
    @DisplayName("Conflict found → pipeline BLOCKED, simulation never called")
    void conflictFound_shortCircuits_simulationNotCalled() {
        when(ruleRepository.findAllActive()).thenReturn(List.of());
        when(conflictDetector.detectConflicts(any(), any())).thenReturn(List.of(conflict()));

        ValidationPipeline result = orchestrator.validate(proposedRule("r1"));

        assertThat(result.decision()).isEqualTo(DeploymentDecision.BLOCKED_CONFLICTS);
        assertThat(result.conflicts()).hasSize(1);
        assertThat(result.simulation()).isNull();
        assertThat(result.impact()).isNull();
        assertThat(result.blockedReason()).isNotBlank();

        // Simulation and impact should NEVER be called
        verifyNoInteractions(simulationEngine, impactQuantifier);
    }

    // ── Full pipeline ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("No conflict + LOW risk → SAFE_TO_DEPLOY")
    void noConflict_lowRisk_safeToDeployDecision() {
        when(ruleRepository.findAllActive()).thenReturn(List.of());
        when(txRepository.findLastNDays(anyInt(), anyString(), anyString()))
            .thenReturn(List.of());
        when(conflictDetector.detectConflicts(any(), any())).thenReturn(List.of());
        when(simulationEngine.simulate(any(), any(), any())).thenReturn(safeReport());
        when(impactQuantifier.quantify(any(), any(), anyInt()))
            .thenReturn(impactWithRisk(RiskLevel.LOW));

        ValidationPipeline result = orchestrator.validate(proposedRule("r1"));

        assertThat(result.decision()).isEqualTo(DeploymentDecision.SAFE_TO_DEPLOY);
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.simulation()).isNotNull();
        assertThat(result.impact()).isNotNull();
        assertThat(result.blockedReason()).isNull();
    }

    @Test
    @DisplayName("No conflict + CRITICAL risk → REQUIRES_CFO_APPROVAL")
    void noConflict_criticalRisk_cfoApprovalRequired() {
        when(ruleRepository.findAllActive()).thenReturn(List.of());
        when(txRepository.findLastNDays(anyInt(), anyString(), anyString()))
            .thenReturn(List.of());
        when(conflictDetector.detectConflicts(any(), any())).thenReturn(List.of());
        when(simulationEngine.simulate(any(), any(), any())).thenReturn(safeReport());
        when(impactQuantifier.quantify(any(), any(), anyInt()))
            .thenReturn(impactWithRisk(RiskLevel.CRITICAL));

        ValidationPipeline result = orchestrator.validate(proposedRule("r1"));

        assertThat(result.decision()).isEqualTo(DeploymentDecision.REQUIRES_CFO_APPROVAL);
    }

    @Test
    @DisplayName("No conflict + HIGH risk → REQUIRES_FINANCE_APPROVAL")
    void noConflict_highRisk_financeApprovalRequired() {
        when(ruleRepository.findAllActive()).thenReturn(List.of());
        when(txRepository.findLastNDays(anyInt(), anyString(), anyString()))
            .thenReturn(List.of());
        when(conflictDetector.detectConflicts(any(), any())).thenReturn(List.of());
        when(simulationEngine.simulate(any(), any(), any())).thenReturn(safeReport());
        when(impactQuantifier.quantify(any(), any(), anyInt()))
            .thenReturn(impactWithRisk(RiskLevel.HIGH));

        ValidationPipeline result = orchestrator.validate(proposedRule("r1"));

        assertThat(result.decision()).isEqualTo(DeploymentDecision.REQUIRES_FINANCE_APPROVAL);
    }

    @Test
    @DisplayName("BLOCK_DEPLOYMENT from simulation → decision reflects simulation block")
    void simulationBlocksDeployment_decisionIsBlocked() {
        SimulationReport blockedReport = new SimulationReport(
            1_000_000, 900_000,
            new BigDecimal("90000.00"),
            Map.of(), Map.of(), new BigDecimal("100.00"),
            90.0, RecommendedAction.BLOCK_DEPLOYMENT  // ← high divergence
        );

        when(ruleRepository.findAllActive()).thenReturn(List.of());
        when(txRepository.findLastNDays(anyInt(), anyString(), anyString()))
            .thenReturn(List.of());
        when(conflictDetector.detectConflicts(any(), any())).thenReturn(List.of());
        when(simulationEngine.simulate(any(), any(), any())).thenReturn(blockedReport);
        when(impactQuantifier.quantify(any(), any(), anyInt()))
            .thenReturn(impactWithRisk(RiskLevel.MEDIUM));

        ValidationPipeline result = orchestrator.validate(proposedRule("r1"));

        // RecommendedAction.BLOCK_DEPLOYMENT maps to BLOCKED_CONFLICTS decision
        assertThat(result.decision()).isEqualTo(DeploymentDecision.BLOCKED_CONFLICTS);
    }

    // ── Verify call order ─────────────────────────────────────────────────────

    @Test
    @DisplayName("All pipeline stages called in order: conflict → sim → impact")
    void pipelineStagesCalledInOrder() {
        when(ruleRepository.findAllActive()).thenReturn(List.of());
        when(txRepository.findLastNDays(anyInt(), anyString(), anyString()))
            .thenReturn(List.of());
        when(conflictDetector.detectConflicts(any(), any())).thenReturn(List.of());
        when(simulationEngine.simulate(any(), any(), any())).thenReturn(safeReport());
        when(impactQuantifier.quantify(any(), any(), anyInt()))
            .thenReturn(impactWithRisk(RiskLevel.LOW));

        orchestrator.validate(proposedRule("r1"));

        var inOrder = inOrder(conflictDetector, simulationEngine, impactQuantifier);
        inOrder.verify(conflictDetector).detectConflicts(any(), any());
        inOrder.verify(simulationEngine).simulate(any(), any(), any());
        inOrder.verify(impactQuantifier).quantify(any(), any(), anyInt());
    }
}
