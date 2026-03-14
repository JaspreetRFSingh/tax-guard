package com.taxguard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.taxguard.domain.*;
import com.taxguard.domain.enums.*;
import com.taxguard.service.ValidationOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for TaxGuardController using MockMvc.
 *
 * Uses @WebMvcTest — spins up only the web layer (no DB, no Kafka).
 * Services are mocked with @MockBean to isolate HTTP concerns.
 *
 * Tests cover:
 *   - Happy path: rule with conflicts → BLOCKED response
 *   - Happy path: clean rule → SAFE_TO_DEPLOY response
 *   - CRITICAL risk → REQUIRES_CFO_APPROVAL in response
 *   - HTTP semantics: correct status codes, Content-Type header
 *   - Response JSON structure: all required fields present
 *   - Error handling: malformed request → 400
 */
@WebMvcTest(TaxGuardController.class)
class TaxGuardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ValidationOrchestrator orchestrator;

    @MockBean
    private com.taxguard.conflict.RuleConflictDetector conflictDetector;

    @MockBean
    private com.taxguard.anomaly.RateAnomalyDetector anomalyDetector;

    @MockBean
    private com.taxguard.repository.TaxRuleRepository ruleRepository;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    // ── Test fixtures ─────────────────────────────────────────────────────────

    private TaxRule sampleProposedRule() {
        return new TaxRule(
            "IN-FOOD-2025-Q2", "IN-KA", "FOOD",
            new BigDecimal("0.12"),
            LocalDate.of(2025, 4, 1), null,
            RulePriority.FEDERAL, "IN-GST-2025.Q2",
            "CGST Amendment 15/2025", RuleStatus.DRAFT
        );
    }

    private SimulationReport safeSimulationReport() {
        return new SimulationReport(
            1_000_000L, 500L,
            new BigDecimal("250.00"),
            Map.of("IN-KA", new BigDecimal("250.00")),
            Map.of("FOOD",  new BigDecimal("250.00")),
            new BigDecimal("1.50"),
            0.05,
            RecommendedAction.SAFE_TO_DEPLOY
        );
    }

    private ImpactReport lowImpactReport() {
        return new ImpactReport(
            "IN-FOOD-2025-Q2", "30 days",
            1_000_000L, 500L,
            new BigDecimal("250.00"),
            new BigDecimal("3041.67"),
            Map.of("IN-KA", new BigDecimal("250.00")),
            Map.of("FOOD",  new BigDecimal("250.00")),
            new BigDecimal("1.50"),
            RiskLevel.LOW,
            "Rule IN-FOOD-2025-Q2 would INCREASE tax collected by $3,041.67 per year..."
        );
    }

    // ── Validate endpoint ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/taxguard/rules/validate")
    class ValidateRule {

        @Test
        @DisplayName("Conflict detected → BLOCKED response with conflict details")
        void conflictDetected_blockedResponse() throws Exception {
            RuleConflict conflict = new RuleConflict(
                "IN-FOOD-2025-Q2", "IN-FOOD-2024", "IN-KA", "FOOD",
                new BigDecimal("0.12"), new BigDecimal("0.05"),
                "Overlap: 2025-04-01 → ∞",
                "PROPOSED_WINS: set existing effectiveTo = 2025-03-31"
            );

            when(orchestrator.validate(any())).thenReturn(
                ValidationPipeline.blocked("IN-FOOD-2025-Q2", List.of(conflict)));

            mockMvc.perform(post("/api/v1/taxguard/rules/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(sampleProposedRule())))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.proposedRuleId").value("IN-FOOD-2025-Q2"))
                .andExpect(jsonPath("$.decision").value("BLOCKED_CONFLICTS"))
                .andExpect(jsonPath("$.conflicts").isArray())
                .andExpect(jsonPath("$.conflicts[0].existingRuleId").value("IN-FOOD-2024"))
                .andExpect(jsonPath("$.conflicts[0].proposedRate").value(0.12))
                .andExpect(jsonPath("$.conflicts[0].existingRate").value(0.05))
                .andExpect(jsonPath("$.blockedReason").exists())
                .andExpect(jsonPath("$.simulation").doesNotExist());
        }

        @Test
        @DisplayName("No conflicts, low risk → SAFE_TO_DEPLOY with full report")
        void noConflicts_lowRisk_safeToDeployResponse() throws Exception {
            ValidationPipeline pipeline = new ValidationPipeline(
                "IN-FOOD-2025-Q2",
                List.of(),              // no conflicts
                safeSimulationReport(),
                lowImpactReport(),
                DeploymentDecision.SAFE_TO_DEPLOY,
                null
            );

            when(orchestrator.validate(any())).thenReturn(pipeline);

            mockMvc.perform(post("/api/v1/taxguard/rules/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(sampleProposedRule())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("SAFE_TO_DEPLOY"))
                .andExpect(jsonPath("$.conflicts").isArray())
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.simulation.totalTransactions").value(1_000_000))
                .andExpect(jsonPath("$.simulation.divergenceRate").value(0.05))
                .andExpect(jsonPath("$.simulation.recommendedAction").value("SAFE_TO_DEPLOY"))
                .andExpect(jsonPath("$.impact.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.impact.projectedAnnualDelta").exists())
                .andExpect(jsonPath("$.impact.regulatoryNote").isNotEmpty());
        }

        @Test
        @DisplayName("CRITICAL risk → REQUIRES_CFO_APPROVAL decision")
        void criticalRisk_cfoApprovalRequired() throws Exception {
            ImpactReport criticalImpact = new ImpactReport(
                "IN-FOOD-2025-Q2", "30 days",
                10_000_000L, 9_000_000L,
                new BigDecimal("1000000.00"),
                new BigDecimal("12166666.67"),
                Map.of(), Map.of(),
                new BigDecimal("500.00"),
                RiskLevel.CRITICAL,
                "REQUIRES CFO + Legal sign-off before deployment."
            );

            ValidationPipeline pipeline = new ValidationPipeline(
                "IN-FOOD-2025-Q2",
                List.of(),
                safeSimulationReport(),
                criticalImpact,
                DeploymentDecision.REQUIRES_CFO_APPROVAL,
                null
            );

            when(orchestrator.validate(any())).thenReturn(pipeline);

            mockMvc.perform(post("/api/v1/taxguard/rules/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(sampleProposedRule())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REQUIRES_CFO_APPROVAL"))
                .andExpect(jsonPath("$.impact.riskLevel").value("CRITICAL"))
                .andExpect(jsonPath("$.impact.projectedAnnualDelta").value(12166666.67));
        }

        @Test
        @DisplayName("Malformed JSON body → 400 Bad Request")
        void malformedJson_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/taxguard/rules/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{ this is not valid json }"))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Missing Content-Type header → 415 Unsupported Media Type")
        void missingContentType_returns415() throws Exception {
            mockMvc.perform(post("/api/v1/taxguard/rules/validate")
                    .content(objectMapper.writeValueAsString(sampleProposedRule())))
                .andExpect(status().isUnsupportedMediaType());
        }
    }

    // ── Anomaly endpoints ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Anomaly alert endpoints")
    class AnomalyEndpoints {

        @Test
        @DisplayName("GET /anomalies — returns empty list when all clear")
        void getAnomalies_emptyList_whenAllClear() throws Exception {
            when(anomalyDetector.getActiveAlerts()).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/taxguard/anomalies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("GET /anomalies — returns active alerts with all fields")
        void getAnomalies_returnsAlerts() throws Exception {
            com.taxguard.domain.AnomalyAlert alert = new com.taxguard.domain.AnomalyAlert(
                "IN-KA", "FOOD",
                0.18, 0.05, 14.7,
                Severity.CRITICAL,
                "Effective rate 0.1800 deviates 14.7σ from mean 0.0500.",
                "IN-GST-ELECTRONICS-2024",
                java.time.Instant.now()
            );

            when(anomalyDetector.getActiveAlerts()).thenReturn(List.of(alert));

            mockMvc.perform(get("/api/v1/taxguard/anomalies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].jurisdiction").value("IN-KA"))
                .andExpect(jsonPath("$[0].productCategory").value("FOOD"))
                .andExpect(jsonPath("$[0].observedRate").value(0.18))
                .andExpect(jsonPath("$[0].zScore").value(14.7))
                .andExpect(jsonPath("$[0].severity").value("CRITICAL"));
        }

        @Test
        @DisplayName("POST /anomalies/suppress → 200 OK")
        void suppressAlert_returns200() throws Exception {
            mockMvc.perform(post("/api/v1/taxguard/anomalies/suppress")
                    .param("jurisdiction",   "IN-KA")
                    .param("productCategory", "FOOD")
                    .param("ruleVersion",     "IN-GST-2025.Q2"))
                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("POST /anomalies/acknowledge → 200 OK")
        void acknowledgeAlert_returns200() throws Exception {
            mockMvc.perform(post("/api/v1/taxguard/anomalies/acknowledge")
                    .param("jurisdiction",   "IN-KA")
                    .param("productCategory", "FOOD"))
                .andExpect(status().isOk());
        }
    }

    // ── Health endpoint ───────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /health → 200 with status UP and counts")
    void health_returns200WithStatus() throws Exception {
        when(ruleRepository.findAllActive()).thenReturn(List.of());
        when(anomalyDetector.getActiveAlerts()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/taxguard/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.activeRules").isNumber())
            .andExpect(jsonPath("$.activeAnomalies").isNumber());
    }
}
