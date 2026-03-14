package com.taxguard.conflict;

import com.taxguard.domain.*;
import com.taxguard.domain.enums.RulePriority;
import com.taxguard.domain.enums.RuleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleConflictDetectorTest {

    private RuleConflictDetector detector;

    @BeforeEach
    void setUp() {
        detector = new RuleConflictDetector();
    }

    private TaxRule rule(String id, String jur, String cat,
                          String from, String to, double rate,
                          RulePriority priority) {
        return new TaxRule(id, jur, cat,
            BigDecimal.valueOf(rate),
            LocalDate.parse(from),
            to != null ? LocalDate.parse(to) : null,
            priority, "v1", "Regulation Ref", RuleStatus.ACTIVE);
    }

    // ── No conflict cases ─────────────────────────────────────────────────────

    @Test
    @DisplayName("No overlap in time → no conflict")
    void noTemporalOverlap_noConflict() {
        TaxRule existing = rule("r1", "IN-KA", "FOOD", "2024-01-01", "2024-06-30", 0.05, RulePriority.FEDERAL);
        TaxRule proposed = rule("r2", "IN-KA", "FOOD", "2024-07-01", null, 0.05, RulePriority.FEDERAL);

        assertThat(detector.detectConflicts(proposed, List.of(existing))).isEmpty();
    }

    @Test
    @DisplayName("Overlap in time but same rate → no conflict")
    void overlapSameRate_noConflict() {
        TaxRule existing = rule("r1", "IN-KA", "FOOD", "2024-01-01", null, 0.05, RulePriority.FEDERAL);
        TaxRule proposed = rule("r2", "IN-KA", "FOOD", "2024-06-01", null, 0.05, RulePriority.FEDERAL);

        assertThat(detector.detectConflicts(proposed, List.of(existing))).isEmpty();
    }

    @Test
    @DisplayName("Different jurisdiction → no conflict")
    void differentJurisdiction_noConflict() {
        TaxRule existing = rule("r1", "IN-KA", "FOOD", "2024-01-01", null, 0.05, RulePriority.FEDERAL);
        TaxRule proposed = rule("r2", "US-CA", "FOOD", "2024-01-01", null, 0.0725, RulePriority.STATE);

        assertThat(detector.detectConflicts(proposed, List.of(existing))).isEmpty();
    }

    @Test
    @DisplayName("Different product category → no conflict")
    void differentCategory_noConflict() {
        TaxRule existing = rule("r1", "IN-KA", "FOOD", "2024-01-01", null, 0.05, RulePriority.FEDERAL);
        TaxRule proposed = rule("r2", "IN-KA", "RIDES", "2024-01-01", null, 0.05, RulePriority.FEDERAL);

        assertThat(detector.detectConflicts(proposed, List.of(existing))).isEmpty();
    }

    @Test
    @DisplayName("SUPERSEDED rules are ignored in conflict detection")
    void supersededRule_ignored() {
        TaxRule superseded = new TaxRule("r-old", "IN-KA", "FOOD",
            BigDecimal.valueOf(0.12),
            LocalDate.of(2022, 1, 1), LocalDate.of(2023, 12, 31),
            RulePriority.FEDERAL, "v-old", null, RuleStatus.SUPERSEDED);

        TaxRule proposed = rule("r-new", "IN-KA", "FOOD", "2024-01-01", null, 0.05, RulePriority.FEDERAL);

        assertThat(detector.detectConflicts(proposed, List.of(superseded))).isEmpty();
    }

    // ── Conflict cases ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Overlap with different rate → conflict detected")
    void overlapDifferentRate_conflictDetected() {
        TaxRule existing = rule("r1", "IN-KA", "FOOD", "2024-01-01", null, 0.05, RulePriority.FEDERAL);
        TaxRule proposed = rule("r2", "IN-KA", "FOOD", "2024-06-01", null, 0.12, RulePriority.FEDERAL);

        List<RuleConflict> conflicts = detector.detectConflicts(proposed, List.of(existing));

        assertThat(conflicts).hasSize(1);
        RuleConflict c = conflicts.get(0);
        assertThat(c.proposedRuleId()).isEqualTo("r2");
        assertThat(c.existingRuleId()).isEqualTo("r1");
        assertThat(c.proposedRate()).isEqualByComparingTo("0.12");
        assertThat(c.existingRate()).isEqualByComparingTo("0.05");
    }

    @Test
    @DisplayName("Higher priority proposed rule → resolution advice mentions effectiveTo")
    void higherPriorityProposed_resolutionSetsEffectiveTo() {
        TaxRule existing = rule("r1", "IN-KA", "FOOD", "2024-01-01", null, 0.05, RulePriority.DEFAULT);
        TaxRule proposed = rule("r2", "IN-KA", "FOOD", "2024-06-01", null, 0.12, RulePriority.FEDERAL);

        List<RuleConflict> conflicts = detector.detectConflicts(proposed, List.of(existing));

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).resolutionAdvice())
            .contains("PROPOSED_WINS")
            .contains("effectiveTo")
            .contains("2024-05-31"); // day before proposed start
    }

    @Test
    @DisplayName("Equal priority, different rates → manual review required")
    void equalPriority_differentRates_manualReview() {
        TaxRule existing = rule("r1", "IN-KA", "FOOD", "2024-01-01", null, 0.05, RulePriority.FEDERAL);
        TaxRule proposed = rule("r2", "IN-KA", "FOOD", "2024-06-01", null, 0.12, RulePriority.FEDERAL);

        List<RuleConflict> conflicts = detector.detectConflicts(proposed, List.of(existing));

        assertThat(conflicts.get(0).resolutionAdvice()).contains("MANUAL_REVIEW_REQUIRED");
    }

    // ── detectAllConflicts ────────────────────────────────────────────────────

    @Test
    @DisplayName("detectAllConflicts finds all pairwise conflicts across rule store")
    void detectAllConflicts_findsAllPairs() {
        List<TaxRule> allRules = List.of(
            rule("r1", "IN-KA", "FOOD", "2024-01-01", null, 0.05, RulePriority.FEDERAL),
            rule("r2", "IN-KA", "FOOD", "2024-06-01", null, 0.12, RulePriority.FEDERAL),
            rule("r3", "US-CA", "RIDES", "2024-01-01", null, 0.0725, RulePriority.STATE)
        );

        Map<String, List<RuleConflict>> result = detector.detectAllConflicts(allRules);

        // r1 and r2 conflict (same bucket, overlapping, different rates)
        assertThat(result).containsKey("IN-KA:FOOD");
        assertThat(result.get("IN-KA:FOOD")).hasSize(1);

        // r3 has no partner — no conflict in US-CA:RIDES bucket
        assertThat(result).doesNotContainKey("US-CA:RIDES");
    }
}
