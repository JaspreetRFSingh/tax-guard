package com.taxguard.repository;

import com.taxguard.domain.enums.RuleStatus;
import com.taxguard.domain.TaxRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaxRuleRepository extends JpaRepository<TaxRule, String> {

    List<TaxRule> findByStatus(RuleStatus status);

    @Query("SELECT r FROM TaxRule r WHERE r.status = 'ACTIVE'")
    List<TaxRule> findAllActive();

    @Query("SELECT r FROM TaxRule r WHERE r.jurisdiction = :jur " +
           "AND r.productCategory = :cat AND r.status = 'ACTIVE'")
    List<TaxRule> findActiveByJurisdictionAndCategory(
        @Param("jur") String jurisdiction,
        @Param("cat") String productCategory);
}
