package com.taxguard.simulation;

import com.taxguard.domain.TaxRule;
import com.taxguard.domain.TaxTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * Stateless, pure calculator used by ShadowSimulationEngine.
 *
 * Given a transaction and a rule set, returns the tax that WOULD be collected.
 * No side effects — safe to call concurrently from multiple threads.
 *
 * Rule selection logic:
 *   1. Filter to rules matching jurisdiction + productCategory
 *   2. Filter to rules effective on the transaction date
 *   3. Among matches, pick highest-priority rule
 *   4. If no rule matches → return ZERO (tax-exempt or unknown category)
 */
@Component
public class RuleBasedTaxCalculator {

    public BigDecimal calculate(TaxTransaction tx, List<TaxRule> ruleSet) {
        return ruleSet.stream()
            .filter(r -> matchesJurisdiction(r, tx.getJurisdiction()))
            .filter(r -> r.getProductCategory().equalsIgnoreCase(tx.getProductCategory()))
            .filter(r -> isEffectiveOn(r, tx.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate()))
            .max(Comparator.comparing(TaxRule::getPriority))
            .map(rule -> tx.getAmount().multiply(rule.getRate()).setScale(4, RoundingMode.HALF_UP))
            .orElse(BigDecimal.ZERO);
    }

    /** Supports wildcard jurisdictions: "IN-*" matches "IN-KA", "IN-MH", etc. */
    private boolean matchesJurisdiction(TaxRule rule, String txJurisdiction) {
        if (rule.getJurisdiction().endsWith("-*")) {
            String prefix = rule.getJurisdiction().replace("-*", "-");
            return txJurisdiction.startsWith(prefix);
        }
        return rule.getJurisdiction().equalsIgnoreCase(txJurisdiction);
    }

    private boolean isEffectiveOn(TaxRule rule, java.time.LocalDate date) {
        boolean afterStart = !date.isBefore(rule.getEffectiveFrom());
        boolean beforeEnd  = rule.getEffectiveTo() == null || date.isBefore(rule.getEffectiveTo());
        return afterStart && beforeEnd;
    }
}
