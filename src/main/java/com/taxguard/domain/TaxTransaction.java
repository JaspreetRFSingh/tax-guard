package com.taxguard.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A historical tax transaction — used as input to ShadowSimulationEngine.
 * Stored in append-only fashion (never updated after creation).
 */
@Entity
@Table(name = "tax_transactions",
       indexes = {
           @Index(name = "idx_tx_jurisdiction_category", columnList = "jurisdiction,productCategory"),
           @Index(name = "idx_tx_created_at", columnList = "createdAt")
       })
public class TaxTransaction {

    @Id
    private String transactionId;

    @Column(nullable = false)
    private String jurisdiction;

    @Column(nullable = false)
    private String productCategory;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal taxCollected;

    @Column(nullable = false)
    private String appliedRuleVersion;

    @Column(nullable = false)
    private Instant createdAt;

    protected TaxTransaction() {}

    public TaxTransaction(String transactionId, String jurisdiction, String productCategory,
                          BigDecimal amount, BigDecimal taxCollected,
                          String appliedRuleVersion, Instant createdAt) {
        this.transactionId = transactionId;
        this.jurisdiction = jurisdiction;
        this.productCategory = productCategory;
        this.amount = amount;
        this.taxCollected = taxCollected;
        this.appliedRuleVersion = appliedRuleVersion;
        this.createdAt = createdAt;
    }

    public String getTransactionId()    { return transactionId; }
    public String getJurisdiction()     { return jurisdiction; }
    public String getProductCategory()  { return productCategory; }
    public BigDecimal getAmount()       { return amount; }
    public BigDecimal getTaxCollected() { return taxCollected; }
    public String getAppliedRuleVersion() { return appliedRuleVersion; }
    public Instant getCreatedAt()       { return createdAt; }
}
