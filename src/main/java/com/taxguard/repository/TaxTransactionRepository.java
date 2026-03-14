package com.taxguard.repository;

import com.taxguard.domain.TaxTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaxTransactionRepository extends JpaRepository<TaxTransaction, String> {

    /**
     * Fetch historical transactions for a specific jurisdiction and product category
     * within the last N days. Used by ShadowSimulationEngine.
     *
     * Restricts to the relevant (jurisdiction × category) bucket to keep simulation
     * data volumes manageable — no point simulating US transactions for an India rule change.
     */
    @Query(value = """
        SELECT * FROM tax_transactions
        WHERE jurisdiction LIKE :jurisdictionPattern
          AND product_category = :category
          AND created_at >= NOW() - INTERVAL ':days days'
        ORDER BY created_at DESC
        """, nativeQuery = true)
    List<TaxTransaction> findLastNDays(
        @Param("days")                int days,
        @Param("jurisdictionPattern") String jurisdictionPattern,
        @Param("category")            String category);

    /**
     * Overloaded: fetch ALL transactions for the last N days (used for full audits).
     */
    @Query(value = """
        SELECT * FROM tax_transactions
        WHERE created_at >= NOW() - INTERVAL ':days days'
        ORDER BY created_at DESC
        """, nativeQuery = true)
    List<TaxTransaction> findAllLastNDays(@Param("days") int days);
}
