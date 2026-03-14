package com.taxguard.conflict;

import com.taxguard.domain.*;
import com.taxguard.domain.enums.RulePriority;
import com.taxguard.domain.enums.RuleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive tests for IntervalTree.
 *
 * Tests cover all overlap categories:
 *   (A) Complete containment  [---[query]---]
 *   (B) Left partial overlap  [rule]
 *                                  [query]
 *   (C) Right partial overlap       [rule]
 *                             [query]
 *   (D) No overlap            [rule]  [query]
 *   (E) Open-ended interval   [rule → ∞]  with future query
 *   (F) Edge case: query endpoint = rule endpoint (inclusive boundary)
 */
class IntervalTreeTest {

    private IntervalTree<TaxRule> tree;

    @BeforeEach
    void setUp() {
        tree = new IntervalTree<>();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TaxRule rule(String from, String to, double rate) {
        return new TaxRule("rule-" + from + "-" + (to != null ? to : "open"),
            "IN-KA", "FOOD",
            BigDecimal.valueOf(rate),
            LocalDate.parse(from),
            to != null ? LocalDate.parse(to) : null,
            RulePriority.FEDERAL, "v1", null, RuleStatus.ACTIVE);
    }

    private List<TaxRule> query(String from, String to) {
        return tree.queryOverlapping(
            LocalDate.parse(from),
            to != null ? LocalDate.parse(to) : LocalDate.MAX);
    }

    // ── Basic cases ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Empty tree")
    class EmptyTree {
        @Test
        void query_returnsEmpty() {
            assertThat(query("2024-01-01", "2024-12-31")).isEmpty();
        }

        @Test
        void size_isZero() {
            assertThat(tree.size()).isZero();
            assertThat(tree.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("Single rule")
    class SingleRule {
        @Test
        @DisplayName("Query within rule range → returns rule")
        void queryInside_returnsRule() {
            TaxRule r = rule("2024-01-01", "2024-12-31", 0.05);
            tree.insert(r);

            assertThat(query("2024-06-01", "2024-08-31")).containsExactly(r);
        }

        @Test
        @DisplayName("Query completely outside rule range → empty")
        void queryOutside_returnsEmpty() {
            tree.insert(rule("2024-01-01", "2024-06-30", 0.05));

            assertThat(query("2025-01-01", "2025-12-31")).isEmpty();
        }

        @Test
        @DisplayName("Query partially overlapping left end → returns rule")
        void queryLeftOverlap_returnsRule() {
            TaxRule r = rule("2024-04-01", "2024-09-30", 0.05);
            tree.insert(r);

            // Query starts before rule, ends inside rule
            assertThat(query("2024-01-01", "2024-06-30")).containsExactly(r);
        }

        @Test
        @DisplayName("Query partially overlapping right end → returns rule")
        void queryRightOverlap_returnsRule() {
            TaxRule r = rule("2024-01-01", "2024-06-30", 0.05);
            tree.insert(r);

            // Query starts inside rule, ends after rule
            assertThat(query("2024-04-01", "2024-12-31")).containsExactly(r);
        }
    }

    @Nested
    @DisplayName("Multiple rules — partial and complete overlaps")
    class MultipleRules {
        @Test
        @DisplayName("Two overlapping rules — both returned for overlapping query")
        void twoOverlapping_bothReturned() {
            TaxRule r1 = rule("2024-01-01", "2024-06-30", 0.05);
            TaxRule r2 = rule("2024-04-01", "2024-09-30", 0.08);
            tree.insert(r1);
            tree.insert(r2);

            List<TaxRule> results = query("2024-04-01", "2024-06-30");

            assertThat(results).containsExactlyInAnyOrder(r1, r2);
        }

        @Test
        @DisplayName("Gap between rules — query in gap returns empty")
        void gapBetweenRules_queryInGap_returnsEmpty() {
            tree.insert(rule("2023-01-01", "2023-06-30", 0.05));
            tree.insert(rule("2024-01-01", "2024-12-31", 0.08));

            // Query in the gap: H2 2023
            assertThat(query("2023-07-01", "2023-12-31")).isEmpty();
        }

        @Test
        @DisplayName("Three rules — query only overlaps two")
        void threeRules_queryTwoOfThree() {
            TaxRule r1 = rule("2024-01-01", "2024-06-30", 0.05);
            TaxRule r2 = rule("2024-04-01", "2024-09-30", 0.08);
            TaxRule r3 = rule("2025-01-01", "2025-12-31", 0.12);
            tree.insert(r1); tree.insert(r2); tree.insert(r3);

            assertThat(query("2024-04-01", "2024-06-30"))
                .containsExactlyInAnyOrder(r1, r2)
                .doesNotContain(r3);
        }
    }

    @Nested
    @DisplayName("Open-ended rules (null effectiveTo)")
    class OpenEndedRules {
        @Test
        @DisplayName("Open-ended rule overlaps any future query")
        void openEndedRule_overlapsAllFutureQueries() {
            TaxRule r = rule("2020-01-01", null, 0.18); // active indefinitely
            tree.insert(r);

            assertThat(query("2030-01-01", "2030-12-31")).containsExactly(r);
            assertThat(query("2099-01-01", "2099-12-31")).containsExactly(r);
        }

        @Test
        @DisplayName("Open-ended rule does NOT overlap queries before its start")
        void openEndedRule_doesNotOverlapBeforeStart() {
            tree.insert(rule("2024-01-01", null, 0.18));

            assertThat(query("2022-01-01", "2023-12-31")).isEmpty();
        }

        @Test
        @DisplayName("Mix of bounded and open-ended rules")
        void mixedRules_correctSubsetReturned() {
            TaxRule bounded  = rule("2024-01-01", "2024-12-31", 0.05);
            TaxRule openEnded = rule("2024-06-01", null, 0.08);
            tree.insert(bounded);
            tree.insert(openEnded);

            // H2 2024: both overlap
            assertThat(query("2024-06-01", "2024-12-31"))
                .containsExactlyInAnyOrder(bounded, openEnded);

            // 2025: only open-ended
            assertThat(query("2025-01-01", "2025-12-31"))
                .containsExactly(openEnded);
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {
        @Test
        @DisplayName("Query endpoint exactly equals rule start → overlap (inclusive boundary)")
        void queryEndEqualsRuleStart_isOverlap() {
            TaxRule r = rule("2024-06-01", "2024-12-31", 0.05);
            tree.insert(r);

            // Query ends exactly on rule's start date → should overlap
            assertThat(query("2024-01-01", "2024-06-01")).containsExactly(r);
        }

        @Test
        @DisplayName("Large number of rules — tree still returns correct results")
        void largeRuleSet_correctResults() {
            // Insert 500 non-overlapping annual rules
            for (int year = 1524; year < 2024; year++) {
                tree.insert(rule(year + "-01-01", year + "-12-31", 0.05));
            }

            assertThat(tree.size()).isEqualTo(500);

            List<TaxRule> results = query("2023-06-01", "2023-08-31");
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getEffectiveFrom().getYear()).isEqualTo(2023);
        }
    }
}
