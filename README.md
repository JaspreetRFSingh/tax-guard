# TaxGuard — Tax Rule Safe Deployment Platform

> Platform demonstrating the engineering problems faced by Uber's Tax Calculations team at global scale.
>
> Built with: Java 17 · Spring Boot 3 · PostgreSQL · Redis · Apache Kafka · Resilience4j · Micrometer

---

## The Problem

Which problem is it trying to solve? 
Tax rules change frequently — new regulations, court rulings, economic shifts. Finance teams must update tax rules in the system to stay compliant and optimize tax collection. But how can they be sure a new rule won't break existing calculations or cause financial risk?
At Ola/Uber/Zomato/Swiggy scale — millions of daily transactions across 70+ countries — a single wrong rule deployed globally can trigger millions in penalty exposure within hours:

- **Under-collection** → under-remittance to tax authority → fines
- **Over-collection** → refund liability + regulatory action
- **Wrong category mapping** → silent mis-calculation across entire product lines
- **Cache serving stale rules** → wrong rate applied for hours before detection

Software engineers deploy new code with CI/CD pipelines, canary rollouts, shadow traffic, and automated rollback. 
Tax rule changes get none of this. **TaxGuard fixes that.**

---

## What TaxGuard Does

TaxGuard applies CI/CD principles to tax rule changes. 
A new rule is treated like a pull request — it must pass four gates before touching production.

```
[1] CONFLICT DETECTION     → Is this rule temporally ambiguous with an existing one?
[2] SHADOW SIMULATION      → What would have happened if this rule was live last month?
[3] IMPACT QUANTIFICATION  → What's the financial delta? Who needs to approve?
[4] ANOMALY DETECTION      → Is the live Kafka stream behaving within expected bounds?
```

---

## Architecture

```
POST /api/v1/taxguard/rules/validate
        │
        ▼
┌────────────────────────────────────────────────────────────┐
│                   ValidationOrchestrator                   │
│                                                            │
│  [1] RuleConflictDetector    O(N log N) Interval Tree      │
│      ↓ blocked if conflicts                                │
│  [2] ShadowSimulationEngine  Parallel CompletableFuture    │
│      ↓ 30 days × historical transactions                   │
│  [3] ImpactQuantifier        Financial delta + risk tier   │
│      ↓ deployment decision                                 │
│  ValidationPipeline response                               │
└────────────────────────────────────────────────────────────┘

Kafka topic: tax.calculation.events
        │
        ▼
┌────────────────────────────────────────────────────────────┐
│              RateAnomalyDetector (always-on)               │
│  Rolling Z-score per (jurisdiction × productCategory)      │
│  |Z| > 2.5σ → AnomalyAlert → PagerDuty                    │
└────────────────────────────────────────────────────────────┘
```

---

## Key Algorithms

### 1. Interval Tree — O(log N) conflict detection

A tax rule's `effectiveFrom`..`effectiveTo` is a **date interval**. Two rules conflict when their intervals overlap AND they produce different rates for the same `(jurisdiction × productCategory)` bucket.

Naive comparison is O(N²). TaxGuard uses an **augmented BST (Interval Tree)**:
- Each node stores a rule + `maxEnd`: the maximum `effectiveTo` in its subtree
- Query: if `node.maxEnd < queryStart`, prune the entire subtree
- **Build: O(N log N) · Query: O(log N + K)**

### 2. Parallel Shadow Simulation

Historical transactions are partitioned into 10K-item batches. Each batch is processed by a `CompletableFuture` on a **dedicated `ExecutorService`** (not `commonPool` — isolation matters).

For each transaction: compute tax under OLD rules (baseline) → compute under PROPOSED rules → record delta.

**~500K transactions/minute on 8 cores.**

### 3. Z-Score Anomaly Detection

For each `(jurisdiction × productCategory)` segment, maintain a rolling window of 1,000 effective tax rates. On each Kafka event:

```
effective_rate = tax_collected / base_amount
Z = (effective_rate − rolling_mean) / rolling_stddev
if |Z| > 2.5 → fire AnomalyAlert
```

Uses **Welford's one-pass algorithm** for numerically stable variance (avoids catastrophic cancellation in naive `sum(x²) - n·mean²`).

---

## Project Structure

```
taxguard/
├── src/main/java/com/taxguard/
│   ├── domain/               # TaxRule, TaxTransaction, SimulationReport, ImpactReport, ...
│   ├── conflict/
│   │   ├── IntervalTree.java           # Augmented BST — core algorithm
│   │   └── RuleConflictDetector.java   # Bucket-partitioned conflict scan
│   ├── simulation/
│   │   ├── ShadowSimulationEngine.java # CompletableFuture parallel replay
│   │   └── RuleBasedTaxCalculator.java # Pure, thread-safe tax computation
│   ├── impact/
│   │   └── ImpactQuantifier.java       # Financial delta + risk tiering
│   ├── anomaly/
│   │   └── RateAnomalyDetector.java    # Kafka consumer + Welford Z-score
│   ├── service/
│   │   └── ValidationOrchestrator.java # Ties all 4 engines together
│   ├── api/
│   │   └── TaxGuardController.java     # REST API
│   └── repository/                     # Spring Data JPA
├── src/test/java/com/taxguard/
│   ├── conflict/IntervalTreeTest.java        # 12 test cases, all overlap categories
│   ├── conflict/RuleConflictDetectorTest.java
│   ├── simulation/ShadowSimulationEngineTest.java
│   └── anomaly/RateAnomalyDetectorTest.java  # Z-score, suppression, acknowledgement
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/V1__initial_schema.sql   # Flyway — seed rules included
└── pom.xml
```

---

## Quick Start

**Prerequisites:** Java 17+, Maven 3.9+, Postgres, Redis, Kafka (running locally)

## Local Setup (macOS)

```bash
# Install dependencies
brew install postgresql@16 redis kafka

# Start services
brew services start postgresql@16
brew services start redis
brew services start kafka

```

If the user or database already exists, skip the create commands. If Kafka fails to start, run `brew info kafka` for the required local setup.

```bash
# 1. Start all dependencies locally (Postgres, Redis, Kafka)

# 2. Run the service
mvn spring-boot:run

# 3. Validate a proposed rule (India GST food rate change 5% → 12%)
curl -X POST http://localhost:8080/api/v1/taxguard/rules/validate \
  -H 'Content-Type: application/json' \
  -d '{
    "ruleId":           "IN-FOOD-2025-Q2",
    "jurisdiction":     "IN-KA",
    "productCategory":  "FOOD",
    "rate":             0.12,
    "effectiveFrom":    "2025-04-01",
    "effectiveTo":      null,
    "priority":         "FEDERAL",
    "ruleVersion":      "IN-GST-2025.Q2",
    "sourceRegulation": "CGST Amendment Notification 15/2025",
    "status":           "DRAFT"
  }'

# 4. Audit all active rule conflicts
curl http://localhost:8080/api/v1/taxguard/rules/conflicts

# 5. View active anomaly alerts
curl http://localhost:8080/api/v1/taxguard/anomalies

# 6. API docs
open http://localhost:8080/swagger-ui.html

# 7. Metrics (Prometheus scrape endpoint)
open http://localhost:8080/actuator/prometheus

# 8. Run tests with coverage report
mvn test jacoco:report
open target/site/jacoco/index.html
```

---

## Sample Response

`POST /api/v1/taxguard/rules/validate` — India GST FOOD rate change:

```json
{
  "proposedRuleId": "IN-FOOD-2025-Q2",
  "conflicts": [{
    "existingRuleId":   "IN-FOOD-2024",
    "proposedRate":      0.12,
    "existingRate":      0.05,
    "conflictPeriod":   "Overlap: 2025-04-01 → ∞ (open-ended)",
    "resolutionAdvice": "PROPOSED_WINS: set existing rule 'IN-FOOD-2024' effectiveTo = 2025-03-31"
  }],
  "simulation": {
    "totalTransactions":   42000000,
    "divergentCount":      38400000,
    "totalDelta":          1234560.00,
    "deltaByJurisdiction": { "IN-KA": 287340.00, "IN-MH": 412600.00 },
    "divergenceRate":      91.4,
    "recommendedAction":   "BLOCK_DEPLOYMENT"
  },
  "impact": {
    "projectedAnnualDelta": 15019480.00,
    "riskLevel":            "CRITICAL",
    "regulatoryNote":       "Rule IN-FOOD-2025-Q2 would INCREASE tax collected by $15,019,480.00 per year... REQUIRES CFO + Legal sign-off before deployment."
  },
  "decision":       "REQUIRES_CFO_APPROVAL",
  "blockedReason":  "1 conflict(s) detected. Resolve before simulation can run."
}
```

---

## API Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/taxguard/rules/validate` | Run full 3-stage validation pipeline |
| `GET`  | `/api/v1/taxguard/rules/conflicts` | Audit all active rule conflicts |
| `GET`  | `/api/v1/taxguard/anomalies` | Current Z-score anomaly alerts |
| `POST` | `/api/v1/taxguard/anomalies/suppress` | Pre-register expected rate change |
| `POST` | `/api/v1/taxguard/anomalies/acknowledge` | Clear alert after investigation |
| `GET`  | `/api/v1/taxguard/health` | Service health + active rule count |

---

## Extension Ideas

- **Rule DSL**: A YAML-based expression language so finance teams can author rules without code
- **Seasonality-aware anomaly detection**: Replace Z-score with STL decomposition or ARIMA to account for Diwali/holiday volume spikes
- **Multi-region rule sync**: Conflict detection across geographically distributed rule stores with eventual consistency
- **gRPC transport**: Replace REST with gRPC for lower-latency inter-service calls (Uber's internal standard)
- **Automatic rollback**: Hook anomaly alerts into a deployment system to auto-revert the most recent rule change

---

*Built as a portfolio demonstration of engineering problems faced by Uber's Tax Calculations platform team.*
