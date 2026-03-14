package com.taxguard.anomaly;

import com.taxguard.domain.enums.Severity;
import com.taxguard.domain.TaxCalculationEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RateAnomalyDetectorTest {

    private RateAnomalyDetector detector;

    @BeforeEach
    void setUp() {
        detector = new RateAnomalyDetector(new SimpleMeterRegistry());
    }

    private TaxCalculationEvent event(String jur, String cat,
                                       double amount, double tax, String ruleVersion) {
        return new TaxCalculationEvent(
            java.util.UUID.randomUUID().toString(),
            jur, cat,
            BigDecimal.valueOf(amount),
            BigDecimal.valueOf(tax),
            ruleVersion,
            Instant.now()
        );
    }

    // Pump N identical events to build rolling window
    private void pumpStableEvents(String jur, String cat,
                                   double amount, double rate, int count) {
        for (int i = 0; i < count; i++) {
            detector.onCalculationEvent(event(jur, cat, amount, amount * rate, "v-stable"));
        }
    }

    // ── Z-score math ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Z-score of mean is zero")
    void zscore_ofMean_isZero() {
        double[] arr = {0.05, 0.05, 0.05, 0.05, 0.05};
        double mean  = detector.mean(arr);
        double std   = detector.stddev(arr, mean);

        assertThat(mean).isCloseTo(0.05, within(1e-9));
        assertThat(std).isCloseTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("Z-score of 3σ outlier is approximately 3")
    void zscore_threeStdDevOutlier() {
        // mean=0.05, std=0.01, so 0.08 is 3σ away
        double[] arr = new double[100];
        java.util.Arrays.fill(arr, 0.05);
        double mean = detector.mean(arr);
        double std  = 0.01; // artificial std

        double z = (0.08 - mean) / std;
        assertThat(z).isCloseTo(3.0, within(0.01));
    }

    // ── Detector behaviour ────────────────────────────────────────────────────

    @Test
    @DisplayName("Stable rate history → no alert fired")
    void stableRate_noAlert() {
        pumpStableEvents("IN-KA", "FOOD", 1000, 0.05, 200);

        // One more event at exactly the same rate
        detector.onCalculationEvent(event("IN-KA", "FOOD", 1000, 50, "v-stable"));

        assertThat(detector.getActiveAlerts()).isEmpty();
    }

    @Test
    @DisplayName("Sudden rate spike → anomaly alert fired")
    void suddenRateSpike_alertFired() {
        // Build stable history at 5%
        pumpStableEvents("IN-KA", "FOOD", 1000, 0.05, 200);

        // Now send an event at 18% (wrong rule — electronics rate applied to food)
        detector.onCalculationEvent(event("IN-KA", "FOOD", 1000, 180, "v-bug"));

        assertThat(detector.getActiveAlerts()).hasSize(1);
        assertThat(detector.getActiveAlerts().get(0).jurisdiction()).isEqualTo("IN-KA");
        assertThat(detector.getActiveAlerts().get(0).productCategory()).isEqualTo("FOOD");
        assertThat(detector.getActiveAlerts().get(0).zScore()).isGreaterThan(RateAnomalyDetector.Z_THRESHOLD);
    }

    @Test
    @DisplayName("Extreme spike → CRITICAL severity")
    void extremeSpike_criticalSeverity() {
        pumpStableEvents("IN-KA", "RIDES", 500, 0.05, 200);

        // 0% tax — tax exemption logic inverted (bug)
        detector.onCalculationEvent(event("IN-KA", "RIDES", 500, 0, "v-bug"));

        assertThat(detector.getActiveAlerts()).isNotEmpty();
        // Z-score for 0 vs mean=0.05 with tiny stddev will be enormous
        assertThat(detector.getActiveAlerts().get(0).severity())
            .isEqualTo(Severity.CRITICAL);
    }

    @Test
    @DisplayName("Alert suppressed for pre-registered rule change → no alert")
    void alertSuppressed_noAlertFired() {
        pumpStableEvents("EU-DE", "RIDES", 100, 0.19, 200);

        // Pre-register: we KNOW the rate is changing to 21%
        detector.suppressAlertsForSegment("EU-DE", "RIDES", "EU-VAT-2025-Q1");

        // Rate changes — would normally trigger alert
        detector.onCalculationEvent(event("EU-DE", "RIDES", 100, 21, "EU-VAT-2025-Q1"));

        assertThat(detector.getActiveAlerts()).isEmpty();
    }

    @Test
    @DisplayName("Alert acknowledged → removed from active list")
    void alertAcknowledged_removedFromList() {
        pumpStableEvents("US-CA", "FOOD", 200, 0.0, 200);  // CA food is 0%

        // Bug: suddenly charging 7.25%
        detector.onCalculationEvent(event("US-CA", "FOOD", 200, 14.5, "v-bug"));
        assertThat(detector.getActiveAlerts()).hasSize(1);

        detector.acknowledgeAlert("US-CA", "FOOD");
        assertThat(detector.getActiveAlerts()).isEmpty();
    }

    @Test
    @DisplayName("Insufficient samples → no alert even for anomalous rate")
    void insufficientSamples_noAlert() {
        // Only pump 50 events (below MIN_SAMPLES=100)
        pumpStableEvents("IN-MH", "ELECTRONICS", 1000, 0.18, 50);

        // Anomalous event
        detector.onCalculationEvent(event("IN-MH", "ELECTRONICS", 1000, 500, "v-bug"));

        // No alert because we don't have enough history to establish a baseline
        assertThat(detector.getActiveAlerts()).isEmpty();
    }
}
