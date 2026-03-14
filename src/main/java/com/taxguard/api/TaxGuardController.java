package com.taxguard.api;

import com.taxguard.anomaly.RateAnomalyDetector;
import com.taxguard.conflict.RuleConflictDetector;
import com.taxguard.domain.*;
import com.taxguard.repository.TaxRuleRepository;
import com.taxguard.service.ValidationOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * TaxGuard REST API.
 *
 * Endpoints:
 *   POST /api/v1/taxguard/rules/validate       Run full validation pipeline
 *   GET  /api/v1/taxguard/rules/conflicts       Audit all active rule conflicts
 *   GET  /api/v1/taxguard/anomalies             Current anomaly alerts
 *   POST /api/v1/taxguard/anomalies/suppress    Pre-register an expected rate change
 *   POST /api/v1/taxguard/anomalies/acknowledge Clear an alert after investigation
 */
@RestController
@RequestMapping("/api/v1/taxguard")
@Tag(name = "TaxGuard", description = "Tax Rule Safe Deployment Platform")
public class TaxGuardController {

    private final ValidationOrchestrator orchestrator;
    private final RuleConflictDetector   conflictDetector;
    private final RateAnomalyDetector    anomalyDetector;
    private final TaxRuleRepository      ruleRepository;

    public TaxGuardController(ValidationOrchestrator orchestrator,
                               RuleConflictDetector conflictDetector,
                               RateAnomalyDetector anomalyDetector,
                               TaxRuleRepository ruleRepository) {
        this.orchestrator      = orchestrator;
        this.conflictDetector  = conflictDetector;
        this.anomalyDetector   = anomalyDetector;
        this.ruleRepository    = ruleRepository;
    }

    // ── Validation pipeline ───────────────────────────────────────────────────

    @PostMapping("/rules/validate")
    @Operation(
        summary = "Validate a proposed rule",
        description = "Runs conflict detection → shadow simulation → impact quantification. " +
                      "Returns a deployment decision with full financial impact report."
    )
    public ResponseEntity<ValidationPipeline> validateRule(
            @RequestBody TaxRule proposedRule) {
        ValidationPipeline result = orchestrator.validate(proposedRule);
        return ResponseEntity.ok(result);
    }

    // ── Conflict audit ────────────────────────────────────────────────────────

    @GetMapping("/rules/conflicts")
    @Operation(
        summary = "Audit all active rule conflicts",
        description = "Scans the entire active rule store for conflicting intervals. " +
                      "Run periodically as a health check."
    )
    public ResponseEntity<Map<String, List<RuleConflict>>> auditAllConflicts() {
        List<TaxRule> active = ruleRepository.findAllActive();
        Map<String, List<RuleConflict>> conflicts =
            conflictDetector.detectAllConflicts(active);
        return ResponseEntity.ok(conflicts);
    }

    // ── Anomaly management ────────────────────────────────────────────────────

    @GetMapping("/anomalies")
    @Operation(
        summary = "Get all active anomaly alerts",
        description = "Returns current Z-score anomalies detected on the live Kafka stream. " +
                      "Empty list = all segments behaving normally."
    )
    public ResponseEntity<List<AnomalyAlert>> getActiveAnomalies() {
        return ResponseEntity.ok(anomalyDetector.getActiveAlerts());
    }

    @PostMapping("/anomalies/suppress")
    @Operation(
        summary = "Pre-register an expected rate change",
        description = "Suppresses false-positive alerts for a segment when you KNOW " +
                      "the rate is about to change due to a planned rule deployment."
    )
    public ResponseEntity<Void> suppressAlerts(
            @RequestParam String jurisdiction,
            @RequestParam String productCategory,
            @RequestParam String ruleVersion) {
        anomalyDetector.suppressAlertsForSegment(jurisdiction, productCategory, ruleVersion);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/anomalies/acknowledge")
    @Operation(
        summary = "Acknowledge and clear an alert",
        description = "Call after investigating an anomaly to remove it from the active list."
    )
    public ResponseEntity<Void> acknowledgeAlert(
            @RequestParam String jurisdiction,
            @RequestParam String productCategory) {
        anomalyDetector.acknowledgeAlert(jurisdiction, productCategory);
        return ResponseEntity.ok().build();
    }

    // ── Health ────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status",         "UP",
            "activeRules",    ruleRepository.findAllActive().size(),
            "activeAnomalies", anomalyDetector.getActiveAlerts().size()
        ));
    }
}
