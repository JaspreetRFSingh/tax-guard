# TaxGuard — System Design Diagrams

> Tax Rule Safe Deployment Platform · Full workflow reference

---

## Diagram 1 · System architecture overview

Two entry points feed the platform: a proposed rule from the finance team (pre-deployment path) and a live Kafka stream of every Uber transaction (post-deployment path). Both paths converge on the same goal — ensuring no wrong tax rate is ever live in production.

```mermaid
graph TD
    FT["Finance / tax team\nidentifies regulation change"]
    UB["Uber platform\nlive transaction stream"]

    FT -->|"POST /rules/validate\n(TaxRule JSON)"| VO
    UB -->|"emits TaxCalculationEvent\nper transaction"| KF

    subgraph PLATFORM ["TaxGuard platform"]
        VO["ValidationOrchestrator\npre-deployment pipeline"]
        KF["Kafka\ntax.calculation.events"]
        RAD["RateAnomalyDetector\npost-deployment monitor"]

        VO -->|"conflict-free + low divergence"| RS
        KF -->|"3 consumer threads"| RAD

        subgraph STORES ["Data stores"]
            RS["Rule store\nPostgreSQL — active rules"]
            TL["Transaction log\nPostgreSQL — 30-day history"]
            RW["Rolling windows\nConcurrentHashMap in-memory"]
        end

        VO -->|"reads"| RS
        VO -->|"reads"| TL
        RAD -->|"writes"| RW
    end

    VO -->|"ValidationPipeline response"| OUT1["Deployment decision\n+ regulatory note"]
    RAD -->|"Z-score > 2.5σ"| OUT2["AnomalyAlert\nPagerDuty + API"]

    style PLATFORM fill:#f8f9fa,stroke:#dee2e6,stroke-width:1px
    style STORES fill:#e9ecef,stroke:#ced4da,stroke-width:1px
```

---

## Diagram 2 · Validation pipeline (pre-deployment)

Every proposed rule passes through three sequential gates. Each gate is ordered cheapest-to-most-expensive — conflict detection runs in milliseconds and blocks the pipeline before any slow work begins.

```mermaid
flowchart TD
    START(["New rule submitted\nPOST /rules/validate"])

    START --> CD

    subgraph GATE1 ["Gate 1 · Conflict detection  O(log N)"]
        CD["RuleConflictDetector\nInterval tree per jurisdiction:category bucket"]
        CD --> CF{"Conflicts\nfound?"}
    end

    CF -->|"yes — blocked"| BLOCKED(["BLOCKED_CONFLICTS\nreturned immediately\nSimulation never runs"])
    CF -->|"no — proceed"| SS

    subgraph GATE2 ["Gate 2 · Shadow simulation  parallel"]
        SS["ShadowSimulationEngine\nFetch last 30 days of real transactions"]
        SS --> PAR["Partition into 10K-tx batches\nCompletableFuture per batch on dedicated ExecutorService"]
        PAR --> OLD["Calculate tax\nwith current active rules"]
        PAR --> NEW["Calculate tax\nwith proposed rule set"]
        OLD --> DIFF["Compute delta per transaction\nMerge into SimulationReport"]
        NEW --> DIFF
        DIFF --> DR{"Divergence\nrate?"}
    end

    DR -->|"> 5%"| BLKD2(["BLOCK_DEPLOYMENT\nToo many transactions affected"])
    DR -->|"1–5%"| MR["MANUAL_REVIEW\nProceed with finance flag"]
    DR -->|"< 1%"| SAFE["SAFE_TO_DEPLOY signal\nProceed to impact stage"]
    MR --> IQ
    SAFE --> IQ

    subgraph GATE3 ["Gate 3 · Impact quantification"]
        IQ["ImpactQuantifier\nExtrapolate 30-day delta → annual projection"]
        IQ --> RISK{"Annual\ndelta abs?"}
        RISK -->|"> $10M"| CRIT["CRITICAL\nCFO + Legal sign-off required"]
        RISK -->|"> $1M"| HIGH["HIGH\nVP Finance approval"]
        RISK -->|"> $100K"| MED["MEDIUM\nFinance team approval"]
        RISK -->|"≤ $100K"| LOW["LOW\nInformational only"]
    end

    CRIT --> DEC
    HIGH --> DEC
    MED --> DEC
    LOW --> DEC

    subgraph GATE4 ["Gate 4 · Deployment decision"]
        DEC["resolveDecision()\nin ValidationOrchestrator"]
        DEC --> D1(["SAFE_TO_DEPLOY"])
        DEC --> D2(["REQUIRES_FINANCE_APPROVAL"])
        DEC --> D3(["REQUIRES_CFO_APPROVAL"])
    end

    ALL -->|"every run"| AUDIT[("validation_audit_log\nimmutable append-only")]

    style GATE1 fill:#e8f4f8,stroke:#378ADD,stroke-width:1px
    style GATE2 fill:#f0f8ee,stroke:#639922,stroke-width:1px
    style GATE3 fill:#fdf6e3,stroke:#BA7517,stroke-width:1px
    style GATE4 fill:#f5f0fe,stroke:#7F77DD,stroke-width:1px
```

---

## Diagram 3 · Live transaction monitoring (post-deployment)

Once a rule is live, the anomaly detector watches every transaction on the Kafka stream. It maintains isolated rolling windows per segment and fires statistical alerts when the effective rate deviates unexpectedly.

```mermaid
flowchart TD
    TX["Uber transaction processed\nride / food / freight"]
    TX -->|"tax calculated"| TE["TaxCalculationEvent emitted\njurisdiction · category · baseAmount · taxCollected · ruleVersion"]
    TE --> KF[("Kafka\ntax.calculation.events\n3 partitions")]

    KF -->|"partition 0"| C0["Consumer thread 0"]
    KF -->|"partition 1"| C1["Consumer thread 1"]
    KF -->|"partition 2"| C2["Consumer thread 2"]

    C0 & C1 & C2 --> ER["Compute effective rate\nrate = taxCollected / baseAmount"]

    ER --> WIN["Rolling window lookup\nConcurrentHashMap key: jurisdiction:category\n1,000 samples · evict oldest on full"]

    WIN --> SC{"Sample count\n≥ 100?"}
    SC -->|"no — burn-in period"| SKIP["Skip — insufficient history\nwindow updated only"]
    SC -->|"yes"| ZS["Compute Z-score\nWelford one-pass algorithm\nZ = (rate − mean) / stddev"]

    ZS --> ZT{"abs(Z)\n> 2.5σ?"}
    ZT -->|"no — normal"| NRM["Normal — no alert\nwindow updated"]
    ZT -->|"yes — anomaly"| SUP{"Segment\nsuppressed?"}

    SUP -->|"yes — pre-registered change"| EXP["Log EXPECTED_CHANGE\nno alert fired"]
    SUP -->|"no — unexpected"| SEV{"abs(Z)\n> 5.0σ?"}

    SEV -->|"yes"| CRIT["Severity: CRITICAL\nLikely rule deployment bug"]
    SEV -->|"no"| HIGH["Severity: HIGH\nUnexpected rate drift"]

    CRIT & HIGH --> ALERT["AnomalyAlert created\njurisdiction · category · observedRate · expectedRate · zScore · ruleVersion · detectedAt"]
    ALERT --> ACTS["Active alerts list\nGET /anomalies"]
    ALERT --> PD["PagerDuty notification\non-call engineer"]
    ALERT --> MET["Micrometer counter\ntaxguard.anomalies.fired"]

    subgraph SUPP ["Suppression lifecycle"]
        PRE["POST /anomalies/suppress\nbefore rule deployment"] -->|"registers segment"| SM[("Suppression map\nConcurrentHashMap")]
        ACK["POST /anomalies/acknowledge\nafter investigation"] -->|"clears alert"| ACTS
        SM --> SUP
    end

    style SUPP fill:#f8f9fa,stroke:#dee2e6,stroke-width:1px
```

---

## Diagram 4 · Conflict detection deep dive (interval tree)

Two rules conflict when they share the same `jurisdiction:productCategory` bucket, have overlapping `effectiveFrom..effectiveTo` date intervals, and produce different rates. The interval tree's augmented `maxEnd` field enables O(log N) pruning.

```mermaid
flowchart TD
    PR["Proposed rule\njurisdiction · category · rate · effectiveFrom · effectiveTo"]

    PR --> BK["Compute bucket key\njurisdiction:productCategory\ne.g. IN-KA:FOOD"]

    BK --> FLT["Filter existing ACTIVE rules\nto same bucket only"]

    FLT --> BLD["Build IntervalTree\nfor this bucket\nO(N log N)"]

    BLD --> QRY["Query overlapping intervals\n queryOverlapping(effectiveFrom, effectiveTo)\nO(log N + K)"]

    QRY --> INT{"Augmented BST\npruning check\nnode.maxEnd < queryStart?"}

    INT -->|"yes — prune entire subtree"| SKIP["Skip subtree\nno overlap possible"]
    INT -->|"no — check this node"| CHK{"Node interval\noverlaps query?"}

    CHK -->|"no overlap"| RECURSE["Recurse to children"]
    CHK -->|"overlap found"| RATE{"Same\nrate?"}

    RATE -->|"yes — benign overlap"| RECURSE
    RATE -->|"no — genuine conflict"| CONFLICT["RuleConflict created\nproposedRate · existingRate · conflictPeriod"]

    CONFLICT --> PRI{"Priority\ncomparison"}

    PRI -->|"proposed > existing"| R1["PROPOSED_WINS\nset existing effectiveTo = proposedFrom − 1 day"]
    PRI -->|"existing > proposed"| R2["EXISTING_WINS\nadjust proposed effectiveFrom"]
    PRI -->|"equal priority"| R3["MANUAL_REVIEW_REQUIRED\nambiguous — human decision needed"]
```

---

## Diagram 5 · Shadow simulation internals

The simulation engine partitions historical transactions into 10K-item batches and processes each batch as an independent `CompletableFuture` on a dedicated `ExecutorService` — isolated from the JVM's `commonPool` to avoid starving HTTP threads.

```mermaid
flowchart LR
    HIST[("Transaction log\nlast 30 days\ne.g. 42M rows")]

    HIST --> PART["Partition into batches\n10,000 tx per batch\n= 4,200 batches"]

    PART --> CF["Submit each batch\nas CompletableFuture\non dedicated ExecutorService\n8 threads = 8 parallel batches"]

    subgraph BATCH ["Per-batch processing  runs in parallel"]
        TX1["For each transaction"] --> OLD["Calculate tax\ncurrent active rules\nRuleBasedTaxCalculator"]
        TX1 --> NEW["Calculate tax\nproposed rule set\nRuleBasedTaxCalculator"]
        OLD & NEW --> DELTA["delta = proposed − current\nisDivergent = delta ≠ 0"]
    end

    CF --> BATCH

    BATCH --> JOIN["CompletableFuture.join()\nWait for all batches\nFlatten results"]

    JOIN --> RPT["Build SimulationReport\ntotalTransactions\ndivergentCount\ntotalDelta\ndeltaByJurisdiction\ndeltaByCategory\nmaxSingleTransactionDelta\ndivergenceRate"]

    RPT --> ACT{"divergenceRate"}
    ACT -->|"< 1%"| S1["SAFE_TO_DEPLOY"]
    ACT -->|"1–5%"| S2["MANUAL_REVIEW"]
    ACT -->|"> 5%"| S3["BLOCK_DEPLOYMENT"]
```

---

## Diagram 6 · Rule deployment lifecycle (end to end)

The complete journey from a government announcing a tax change to a rule safely running in production, with the suppression handshake closing the loop back to monitoring.

```mermaid
sequenceDiagram
    actor Finance as Finance team
    participant API as TaxGuard API
    participant VO as ValidationOrchestrator
    participant CD as ConflictDetector
    participant SIM as ShadowSimulation
    participant IQ as ImpactQuantifier
    participant RAD as AnomalyDetector
    participant PROD as Production rule store

    Finance->>API: POST /rules/validate (TaxRule JSON)
    API->>VO: validate(proposedRule)

    VO->>CD: detectConflicts(proposed, activeRules)
    CD-->>VO: [] (no conflicts)

    VO->>SIM: simulate(history, currentRules, proposedRules)
    Note over SIM: Parallel CompletableFuture batches<br/>42M tx replayed in ~90 min
    SIM-->>VO: SimulationReport (divergence 0.05%)

    VO->>IQ: quantify(simulation, proposedRule, 30)
    IQ-->>VO: ImpactReport (risk: LOW, annual delta: $3,041)

    VO-->>API: ValidationPipeline (SAFE_TO_DEPLOY)
    API-->>Finance: 200 OK — decision: SAFE_TO_DEPLOY

    Note over Finance,PROD: Finance approves. Pre-deployment suppression registered.

    Finance->>API: POST /anomalies/suppress (jurisdiction, category, ruleVersion)
    API->>RAD: suppressAlertsForSegment(...)
    RAD-->>API: OK — segment suppressed

    Finance->>PROD: Activate rule in production
    Note over PROD,RAD: Live transactions now use new rate

    RAD->>RAD: Z-score spikes (rate 5% → 12%)
    RAD->>RAD: Check suppression map → segment suppressed
    RAD-->>RAD: Log EXPECTED_CHANGE — no alert fired

    Finance->>API: POST /anomalies/lift-suppression
    Note over RAD: Suppression lifted — detector back on full alert
```

---

## Component summary

| Component | Role | Key algorithm |
|---|---|---|
| `ValidationOrchestrator` | Pipeline coordinator — stages run cheapest-first, short-circuit on block | State machine |
| `RuleConflictDetector` | Finds overlapping rules before simulation runs | Interval tree O(log N + K) |
| `ShadowSimulationEngine` | Replays history through old vs new rules in parallel | `CompletableFuture` batching |
| `ImpactQuantifier` | Translates delta into annual $ projection and risk tier | Linear extrapolation + threshold |
| `RateAnomalyDetector` | Monitors live Kafka stream for unexpected rate changes | Welford Z-score, rolling window |
| `IntervalTree` | Core data structure for O(N log N) conflict scan | Augmented BST with maxEnd pruning |
| `RuleBasedTaxCalculator` | Pure, thread-safe tax computation used in simulation | Priority-ordered rule matching |
| `DataSeeder` | Seeds realistic historical transactions for dev/demo | Log-normal amount distribution |

---

## Complexity reference

| Operation | Complexity | Notes |
|---|---|---|
| Conflict detection — single rule | O(N log N) build + O(log N + K) query | K = conflicting rules found |
| Conflict detection — full audit scan | O(N log N + K) total | N rules across all buckets |
| Shadow simulation | O(T / B × C) | T = transactions, B = batch size, C = cores |
| Z-score per event | O(W) | W = window size (fixed at 1,000) |
| Effective rate lookup | O(1) | ConcurrentHashMap per segment |
