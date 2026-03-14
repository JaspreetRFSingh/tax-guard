package com.taxguard.conflict;

import com.taxguard.domain.*;
import com.taxguard.domain.enums.RuleStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects conflicts between tax rules using an Interval Tree per
 * (jurisdiction × productCategory) bucket.
 *
 * A CONFLICT exists when:
 *   1. Two rules share the same (jurisdiction, productCategory) bucket
 *   2. Their date intervals OVERLAP  [effectiveFrom, effectiveTo]
 *   3. They have DIFFERENT rates
 *
 * Same rates with overlapping intervals are NOT a conflict — the calculation
 * would be identical regardless of which rule wins.
 */
@Service
public class RuleConflictDetector {

    /**
     * Check a single proposed rule against all currently ACTIVE rules.
     * Called as step 1 of the deployment validation pipeline.
     *
     * @param proposedRule  The rule being proposed for deployment
     * @param existingRules All currently ACTIVE rules (fetched from DB)
     * @return List of conflicts; empty list = no conflicts = safe to proceed
     */
    public List<RuleConflict> detectConflicts(TaxRule proposedRule,
                                               List<TaxRule> existingRules) {
        String bucket = bucketKey(proposedRule);

        // Build a tree from existing rules in the same bucket only
        IntervalTree<TaxRule> tree = new IntervalTree<>();
        existingRules.stream()
            .filter(r -> r.getStatus() == RuleStatus.ACTIVE)
            .filter(r -> bucketKey(r).equals(bucket))
            .forEach(tree::insert);

        if (tree.isEmpty()) return List.of();

        LocalDate queryEnd = proposedRule.getEffectiveTo() != null
                ? proposedRule.getEffectiveTo()
                : LocalDate.MAX;

        List<TaxRule> overlapping = tree.queryOverlapping(
                proposedRule.getEffectiveFrom(), queryEnd);

        return overlapping.stream()
            .filter(existing -> existing.getRate().compareTo(proposedRule.getRate()) != 0)
            .map(existing -> buildConflict(proposedRule, existing))
            .collect(Collectors.toList());
    }

    /**
     * Full audit scan: detect ALL pairwise conflicts across the entire rule store.
     * Used for health checks and rule store audits.
     *
     * Groups rules by bucket, then for each rule queries the tree of the others.
     * Overall complexity: O(N log N) build + O(N log N + K) queries.
     *
     * @return Map of bucketKey → list of conflicts in that bucket
     */
    public Map<String, List<RuleConflict>> detectAllConflicts(List<TaxRule> allRules) {
        Map<String, List<TaxRule>> byBucket = allRules.stream()
            .filter(r -> r.getStatus() == RuleStatus.ACTIVE)
            .collect(Collectors.groupingBy(this::bucketKey));

        Map<String, List<RuleConflict>> result = new LinkedHashMap<>();

        byBucket.forEach((bucket, rules) -> {
            List<RuleConflict> bucketConflicts = new ArrayList<>();

            for (int i = 0; i < rules.size(); i++) {
                TaxRule proposed = rules.get(i);
                List<TaxRule> others = rules.subList(i + 1, rules.size());
                bucketConflicts.addAll(detectConflicts(proposed, others));
            }

            if (!bucketConflicts.isEmpty()) {
                result.put(bucket, bucketConflicts);
            }
        });

        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RuleConflict buildConflict(TaxRule proposed, TaxRule existing) {
        return new RuleConflict(
            proposed.getRuleId(),
            existing.getRuleId(),
            proposed.getJurisdiction(),
            proposed.getProductCategory(),
            proposed.getRate(),
            existing.getRate(),
            describeConflictPeriod(proposed, existing),
            resolutionAdvice(proposed, existing)
        );
    }

    /**
     * Resolution logic based on rule priority.
     * Higher-priority rule wins. If equal priority, flag for manual review.
     */
    private String resolutionAdvice(TaxRule proposed, TaxRule existing) {
        int cmp = proposed.getPriority().compareTo(existing.getPriority());

        if (cmp > 0) {
            // Proposed has higher priority — existing should be closed out
            LocalDate suggestedEnd = proposed.getEffectiveFrom().minusDays(1);
            return String.format(
                "PROPOSED_WINS (higher priority). Set existing rule '%s' effectiveTo = %s",
                existing.getRuleId(), suggestedEnd);
        }

        if (cmp < 0) {
            // Existing has higher priority — proposed needs its start date adjusted
            LocalDate existingEnd = existing.getEffectiveTo() != null
                    ? existing.getEffectiveTo()
                    : null;
            return String.format(
                "EXISTING_WINS (higher priority). Adjust proposed effectiveFrom to after %s.",
                existingEnd != null ? existingEnd : "existing rule's expiry (currently open-ended)");
        }

        // Equal priority with different rates — ambiguous, needs human decision
        return String.format(
            "MANUAL_REVIEW_REQUIRED: equal priority %s but rates differ (%.4f vs %.4f). " +
            "A finance or legal decision is needed.",
            proposed.getPriority(), proposed.getRate(), existing.getRate());
    }

    private String describeConflictPeriod(TaxRule a, TaxRule b) {
        LocalDate overlapStart = a.getEffectiveFrom().isAfter(b.getEffectiveFrom())
                ? a.getEffectiveFrom()
                : b.getEffectiveFrom();

        LocalDate endA = a.getEffectiveTo() != null ? a.getEffectiveTo() : LocalDate.MAX;
        LocalDate endB = b.getEffectiveTo() != null ? b.getEffectiveTo() : LocalDate.MAX;
        LocalDate overlapEnd = endA.isBefore(endB) ? endA : endB;

        return String.format("Overlap: %s → %s",
            overlapStart,
            overlapEnd.equals(LocalDate.MAX) ? "∞ (open-ended)" : overlapEnd.toString());
    }

    private String bucketKey(TaxRule rule) {
        return rule.getJurisdiction() + ":" + rule.getProductCategory();
    }
}
