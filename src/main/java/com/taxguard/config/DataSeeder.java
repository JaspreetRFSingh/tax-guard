package com.taxguard.config;

import com.taxguard.domain.TaxTransaction;
import com.taxguard.repository.TaxTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Seeds the database with realistic historical transactions for development and demo.
 *
 * ONLY active in "dev" and "demo" Spring profiles — never runs in production or test.
 *
 * Generates ~100K transactions spread across the last 30 days, modelled on realistic
 * Uber-like transaction distributions:
 *   - India GST: high volume food orders (Uber Eats India)
 *   - India GST: ride hailing
 *   - US Sales Tax: rides and food
 *   - EU VAT: rides
 *
 * The volume and amount distributions are not real Uber data — they are designed
 * to produce realistic Z-score baselines for the anomaly detector.
 */
@Component
@Profile({"dev", "demo"})
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final TaxTransactionRepository txRepository;
    private final Random random = new Random(42L); // Fixed seed for reproducibility

    // Segment: (jurisdiction, category, tax_rate, daily_volume, avg_amount)
    private static final List<Segment> SEGMENTS = List.of(
        new Segment("IN-KA", "FOOD",        0.05, 5_000, 350.0),
        new Segment("IN-MH", "FOOD",        0.05, 8_000, 320.0),
        new Segment("IN-DL", "FOOD",        0.05, 6_000, 380.0),
        new Segment("IN-KA", "RIDES",       0.05, 3_000, 250.0),
        new Segment("IN-MH", "RIDES",       0.05, 4_500, 230.0),
        new Segment("IN-KA", "ELECTRONICS", 0.18, 200,  2500.0),
        new Segment("US-CA", "RIDES",       0.0725, 1_000, 1200.0),
        new Segment("US-TX", "RIDES",       0.0625, 800,  1100.0),
        new Segment("EU-DE", "RIDES",       0.19, 600,  1800.0),
        new Segment("EU-FR", "RIDES",       0.20, 500,  1700.0)
    );

    public DataSeeder(TaxTransactionRepository txRepository) {
        this.txRepository = txRepository;
    }

    @Override
    public void run(String... args) {
        if (txRepository.count() > 0) {
            log.info("[DataSeeder] Transactions already exist — skipping seed.");
            return;
        }

        log.info("[DataSeeder] Seeding historical transactions...");
        Instant startTime = Instant.now();

        List<TaxTransaction> batch = new ArrayList<>(10_000);
        int totalGenerated = 0;

        for (int daysAgo = 30; daysAgo >= 0; daysAgo--) {
            Instant dayBase = Instant.now().minus(daysAgo, ChronoUnit.DAYS);

            for (Segment seg : SEGMENTS) {
                int dailyCount = seg.dailyVolume + random.nextInt(seg.dailyVolume / 5)
                                 - seg.dailyVolume / 10; // ±10% daily variation

                for (int i = 0; i < dailyCount; i++) {
                    // Random time within the day
                    Instant txTime = dayBase.plus(random.nextInt(86_400), ChronoUnit.SECONDS);

                    double amount = generateAmount(seg.avgAmount);
                    double tax    = BigDecimal.valueOf(amount)
                        .multiply(BigDecimal.valueOf(seg.rate))
                        .setScale(4, RoundingMode.HALF_UP)
                        .doubleValue();

                    batch.add(new TaxTransaction(
                        UUID.randomUUID().toString(),
                        seg.jurisdiction,
                        seg.productCategory,
                        BigDecimal.valueOf(amount).setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(tax).setScale(4, RoundingMode.HALF_UP),
                        ruleVersionFor(seg.jurisdiction, seg.productCategory),
                        txTime
                    ));

                    if (batch.size() == 10_000) {
                        txRepository.saveAll(batch);
                        totalGenerated += batch.size();
                        batch.clear();
                        log.debug("[DataSeeder] Saved {} transactions...", totalGenerated);
                    }
                }
            }
        }

        if (!batch.isEmpty()) {
            txRepository.saveAll(batch);
            totalGenerated += batch.size();
        }

        long elapsedMs = Instant.now().toEpochMilli() - startTime.toEpochMilli();
        log.info("[DataSeeder] Seeded {} transactions in {}ms across {} segments over 30 days.",
            totalGenerated, elapsedMs, SEGMENTS.size());
    }

    /**
     * Generate a transaction amount using a log-normal distribution.
     * This produces realistic order amounts:
     * most are near the mean, but some are much larger (large orders, freight, etc.)
     */
    private double generateAmount(double mean) {
        // Log-normal: exp(μ + σZ) where Z ~ N(0,1)
        double sigma = 0.5;
        double mu    = Math.log(mean) - (sigma * sigma) / 2;
        double z     = random.nextGaussian();
        double raw   = Math.exp(mu + sigma * z);
        // Round to nearest rupee/dollar/euro (2 decimal places for FX, 0 for INR)
        return Math.round(raw * 100.0) / 100.0;
    }

    private String ruleVersionFor(String jurisdiction, String category) {
        if (jurisdiction.startsWith("IN-")) return "IN-GST-2024.Q1";
        if (jurisdiction.startsWith("US-")) return "US-SALES-TAX-2024";
        if (jurisdiction.startsWith("EU-")) return "EU-VAT-2024";
        return "UNKNOWN";
    }

    private record Segment(String jurisdiction, String productCategory,
                            double rate, int dailyVolume, double avgAmount) {}
}
