package com.taxguard.domain;

import com.taxguard.conflict.IntervalTree;
import com.taxguard.domain.enums.RulePriority;
import com.taxguard.domain.enums.RuleStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A tax rule is a versioned, time-bounded policy with explicit applicability conditions.
 *
 * Key design decisions:
 * - effectiveFrom/effectiveTo define a DATE INTERVAL — the foundation of conflict detection
 * - null effectiveTo means the rule has no expiry (open-ended, far-future)
 * - ruleVersion is a human-readable semantic version for audit trails
 * - sourceRegulation ties the rule to its legal basis
 * - status tracks the rule through its deployment lifecycle
 */
@Entity
@Table(name = "tax_rules",
       indexes = {
           @Index(name = "idx_jurisdiction_category", columnList = "jurisdiction,productCategory"),
           @Index(name = "idx_effective_from",        columnList = "effectiveFrom"),
           @Index(name = "idx_status",                columnList = "status")
       })
public class TaxRule implements IntervalTree.Interval {

    @Id
    private String ruleId;

    @Column(nullable = false)
    private String jurisdiction;        // e.g., "IN-KA", "US-CA", "EU-DE", "IN-*" (wildcard)

    @Column(nullable = false)
    private String productCategory;     // e.g., "FOOD", "RIDES", "FREIGHT", "ELECTRONICS"

    @Column(nullable = false, precision = 8, scale = 6)
    private BigDecimal rate;            // e.g., 0.050000 for 5%

    @Column(nullable = false)
    private LocalDate effectiveFrom;    // Inclusive

    @Column
    private LocalDate effectiveTo;      // Exclusive; null = no expiry (open-ended)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RulePriority priority;      // FEDERAL > STATE > CITY > DEFAULT

    @Column(nullable = false)
    private String ruleVersion;         // Semantic: "IN-GST-2024.Q4"

    @Column
    private String sourceRegulation;    // e.g., "CGST Act 2017, Section 9(1)"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RuleStatus status;          // DRAFT | PENDING_REVIEW | ACTIVE | SUPERSEDED

    // JPA required
    protected TaxRule() {}

    public TaxRule(String ruleId, String jurisdiction, String productCategory,
                   BigDecimal rate, LocalDate effectiveFrom, LocalDate effectiveTo,
                   RulePriority priority, String ruleVersion,
                   String sourceRegulation, RuleStatus status) {
        this.ruleId = ruleId;
        this.jurisdiction = jurisdiction;
        this.productCategory = productCategory;
        this.rate = rate;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.priority = priority;
        this.ruleVersion = ruleVersion;
        this.sourceRegulation = sourceRegulation;
        this.status = status;
    }

    // Getters
    public String getRuleId()           { return ruleId; }
    public String getJurisdiction()     { return jurisdiction; }
    public String getProductCategory()  { return productCategory; }
    public BigDecimal getRate()         { return rate; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo()   { return effectiveTo; }
    public RulePriority getPriority()   { return priority; }
    public String getRuleVersion()      { return ruleVersion; }
    public String getSourceRegulation() { return sourceRegulation; }
    public RuleStatus getStatus()       { return status; }

    // Implement the Interval interface used by IntervalTree
    public LocalDate getStart() { return effectiveFrom; }
    public LocalDate getEnd()   { return effectiveTo; }

    @Override
    public String toString() {
        return String.format("TaxRule[%s %s:%s %.4f %s→%s %s]",
            ruleId, jurisdiction, productCategory, rate,
            effectiveFrom, effectiveTo != null ? effectiveTo : "∞", status);
    }
}
