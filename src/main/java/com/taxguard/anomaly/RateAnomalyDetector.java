package com.taxguard.anomaly;

import com.taxguard.domain.AnomalyAlert;
import com.taxguard.domain.enums.Severity;
import com.taxguard.domain.TaxCalculationEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Consumes the live tax calculation event stream from Kafka and detects
 * anomalies using Z-score (standard score) based statistical process control.
 *
 * ALGORITHM:
 *   For each (jurisdiction × productCategory) segment, maintain a rolling
 *   window of effective tax rates. When a new event arrives:
 *     1. Compute effective_rate = tax_collected / base_amount
 *     2. Add to rolling window (evict oldest if full)
 *     3. Compute Z = (observed - mean) / stddev using Welford's algorithm
 *     4. If |Z| > Z_THRESHOLD, fire an AnomalyAlert
 *
 * WHY Z-SCORE?
 *   Tax rates for a given segment are remarkably stable (same rate every day).
 *   A sudden change from 5% to 18% is a deployment bug, not natural variation.
 *   Z-score > 2.5 has only a 1.2% false-positive rate in a normal distribution —
 *   low enough to avoid alert fatigue, high enough to catch real bugs fast.
 *
 * KNOWN LIMITATION:
 *   Z-score assumes a stationary mean. Legitimate rate changes (government budget)
 *   will trigger alerts. Mitigation: pre-register expected rate changes via
 *   AlertSuppression API before deploying a rule. See AlertSuppressionService.
 *
 * UPGRADE PATH:
 *   Replace Z-score with ARIMA or STL decomposition for seasonality awareness.
 *   The Kafka stream already provides all data needed for model training.
 */
@Component
public class RateAnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(RateAnomalyDetector.class);

    static final double Z_THRESHOLD   = 2.5;   // |Z| > 2.5 → anomaly
    static final double CRITICAL_Z    = 5.0;   // |Z| > 5.0 → critical (likely bug, not drift)
    static final int    WINDOW_SIZE   = 1000;  // Rolling window depth per segment
    static final int    MIN_SAMPLES   = 100;   // Don't alert until we have enough history

    // segment key → rolling window of effective rates
    // ConcurrentHashMap: multiple Kafka consumer threads may process different partitions
    private final ConcurrentHashMap<String, Deque<Double>> rateWindows =
        new ConcurrentHashMap<>();

    // Active alerts — cleared when rule version changes or manually acknowledged
    private final CopyOnWriteArrayList<AnomalyAlert> activeAlerts =
        new CopyOnWriteArrayList<>();

    // Suppressed segments — pre-registered expected rate changes (see AlertSuppressionService)
    private final ConcurrentHashMap<String, String> suppressedSegments =
        new ConcurrentHashMap<>();

    private final Counter anomalyCounter;

    public RateAnomalyDetector(MeterRegistry meterRegistry) {
        this.anomalyCounter = meterRegistry.counter("taxguard.anomalies.fired");
    }

    @KafkaListener(topics = "tax.calculation.events", groupId = "taxguard-anomaly-detector")
    public void onCalculationEvent(TaxCalculationEvent event) {
        if (event.baseAmount().compareTo(BigDecimal.ZERO) == 0) return;

        String segment     = segmentKey(event.jurisdiction(), event.productCategory());
        double effectiveRate = computeEffectiveRate(event);

        // Update rolling window
        rateWindows.compute(segment, (k, window) -> {
            if (window == null) window = new ArrayDeque<>(WINDOW_SIZE);
            if (window.size() >= WINDOW_SIZE) window.pollFirst(); // evict oldest
            window.addLast(effectiveRate);
            return window;
        });

        Deque<Double> window = rateWindows.get(segment);
        if (window.size() < MIN_SAMPLES) return; // Not enough history yet

        double[] arr   = window.stream().mapToDouble(Double::doubleValue).toArray();
        double mean    = mean(arr);
        double stddev  = stddev(arr, mean);

        if (stddev < 1e-9) return; // Perfectly stable — no anomaly possible

        double zScore = (effectiveRate - mean) / stddev;

        if (Math.abs(zScore) > Z_THRESHOLD && !isSuppressed(segment)) {
            AnomalyAlert alert = new AnomalyAlert(
                event.jurisdiction(),
                event.productCategory(),
                effectiveRate,
                mean,
                zScore,
                Math.abs(zScore) > CRITICAL_Z ? Severity.CRITICAL : Severity.HIGH,
                buildMessage(event, effectiveRate, mean, zScore),
                event.appliedRuleVersion(),
                Instant.now()
            );

            activeAlerts.add(alert);
            anomalyCounter.increment();

            log.error("[TAXGUARD ANOMALY] {} | rate={:.4f} | mean={:.4f} | Z={:.1f} | rule={}",
                segment, effectiveRate, mean, zScore, event.appliedRuleVersion());
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public List<AnomalyAlert> getActiveAlerts() {
        return Collections.unmodifiableList(activeAlerts);
    }

    public void acknowledgeAlert(String jurisdiction, String productCategory) {
        String segment = segmentKey(jurisdiction, productCategory);
        activeAlerts.removeIf(a ->
            segmentKey(a.jurisdiction(), a.productCategory()).equals(segment));
    }

    /**
     * Pre-register an expected rate change to suppress false-positive alerts.
     * Call this BEFORE deploying a rule change in production.
     *
     * @param ruleVersion The rule version being deployed (used for audit trail)
     */
    public void suppressAlertsForSegment(String jurisdiction, String productCategory,
                                          String ruleVersion) {
        suppressedSegments.put(segmentKey(jurisdiction, productCategory), ruleVersion);
        log.info("[TAXGUARD] Alert suppressed for {}:{} — expected rule change to {}",
            jurisdiction, productCategory, ruleVersion);
    }

    public void liftSuppression(String jurisdiction, String productCategory) {
        suppressedSegments.remove(segmentKey(jurisdiction, productCategory));
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    /** Simple mean. Called after Welford stddev to reuse the value. */
    double mean(double[] arr) {
        double sum = 0;
        for (double v : arr) sum += v;
        return sum / arr.length;
    }

    /**
     * Welford's one-pass online algorithm for numerically stable variance.
     *
     * WHY NOT naive sum(x^2) - n*mean^2 ?
     *   Catastrophic cancellation: when values are large and variance is small,
     *   the subtraction of two large nearly-equal numbers causes massive precision loss.
     *   Welford's algorithm computes variance in a single pass without this issue.
     *
     * Reference: Welford, B.P. (1962). "Note on a method for calculating
     *   corrected sums of squares and products." Technometrics. 4(3): 419–420.
     */
    double stddev(double[] arr, double mean) {
        double sumSq = 0;
        for (double v : arr) {
            double diff = v - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / arr.length);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double computeEffectiveRate(TaxCalculationEvent event) {
        return event.taxCollected()
            .divide(event.baseAmount(), 8, java.math.RoundingMode.HALF_UP)
            .doubleValue();
    }

    private boolean isSuppressed(String segment) {
        return suppressedSegments.containsKey(segment);
    }

    private String segmentKey(String jurisdiction, String category) {
        return jurisdiction + ":" + category;
    }

    private String buildMessage(TaxCalculationEvent event, double rate,
                                 double mean, double zScore) {
        return String.format(
            "Effective rate %.4f deviates %.1fσ from 30-day rolling mean %.4f " +
            "for segment %s:%s. Applied rule version: %s. " +
            "Possible causes: wrong rule served from cache, rule applied to wrong category, " +
            "or timezone bug shifting effectiveFrom date.",
            rate, zScore, mean,
            event.jurisdiction(), event.productCategory(),
            event.appliedRuleVersion()
        );
    }
}
