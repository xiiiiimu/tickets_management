# Steady-State Throughput / Capacity Benchmark

**Ladder (scout):** `performance/results/steady-state-20260918-194705/`  
**Stable confirm 20×3:** `performance/results/steady-state-20260918-195239/`  
**Collection only** — no Hikari / pool / JVM / code / timeout changes.

**QPS method (corrected):**  
`successful_reserve_qps = (HTTP 200 with timeStamp ∈ [t0+ramp+warmup, t0+ramp+warmup+60s)) / 60`  
Ramp=10s, warm-up=15s, measure=60s, cool-down=10s. Continuous loop (`loops=-1`). No Constant Throughput Timer.

---

## 1. Environment

| Item | Value |
|---|---|
| git | `578005dab2e453e0333c536d4224afca65489382` (dirty working tree) |
| CPU | 12th Gen Intel Core i7-1260P |
| Docker mem | 768m |
| JVM | `-Xms128m -Xmx384m -XX:+UseSerialGC` |
| Hikari max | **10** |
| autopay | 2 / 4 / **queue 200** |
| JMeter | 5.6.3, response timeout 30s |
| SKU | `perf_sku_steady`, stock=**50000**, purchase_limit=100000 |
| Users | 90001–90200 **excluding BLOCK 90100** |

`ENV_OK` matched FINAL freeze.

---

## 2. Capacity curve (scout, 1 run each)

| Concurrency | Success QPS | P95 | P99 | Sys Err% | Hikari Pending | Hikari Timeout | AutoPay reject (gauge) | Drain WAITING | Status |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| **20** | **15.30** | 2138 | 2550 | 0 | 10 | 0 | 0 | 0 | **STABLE** |
| **50** | 18.02 | 4476 | 5691 | 0 | **45** | 0 | **746** | **746** | **SATURATED** |
| 100 | — | — | — | — | — | — | — | — | **not run** (stop rule) |
| 150 | — | — | — | — | — | — | — | — | not run |
| 200 | — | — | — | — | — | — | — | — | not run |

**Why 50 = SATURATED**

- Success QPS only +18% vs 20, while P95 **2.1×** (2138→4476)
- Hikari **pending peak 45**, active=10 / idle=0 during measure
- AutoPay queue during ≈ **198/200**, then **RejectedExecution** → 746 orders left `WAITING_PAYMENT` after 60s drain
- Correctness `bad_rows=0` still held (no oversell); payment path did **not** fully drain

Stopped raising concurrency per task rules.

---

## 3. Formal stable tier: 20 concurrency × 3

Dir: `steady-state-20260918-195239/`

| Run | Success QPS | P95 | P99 | Sys Err% | Pending | Timeout | bad_rows | Drain WAITING | Status |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| r1 | 16.68 | 1860 | 2104 | 0 | 15 | 0 | 0 | 0 | STABLE |
| r2 | 16.85 | 1747 | 2121 | 0 | 12 | 0 | 0 | 0 | STABLE |
| r3 | 17.45 | 1733 | 2031 | 0 | 12 | 0 | 0 | 0 | STABLE |

| Stat | Success QPS | P95 | P99 |
|---|---:|---:|---:|
| **mean** | **16.99** | **1780** | **2085** |
| min | 16.68 | 1733 | 2031 |
| max | 17.45 | 1860 | 2121 |

Note: `autopay_rejected` gauge remained **746** from the earlier saturated 50-run (same JVM, cumulative). For 20×3, `drain_waiting=0` and orders reached terminal states — reject **delta** for these runs is effectively 0.

---

## 4. Correctness

Scout + confirm: `bad_rows=0`, no sold-out in measure window (`business_409=0`), remaining stock ≫ 0.

---

## 5. Conclusions (required answers)

1. **Highest observed QPS:** **18.02** (50 concurrency, **SATURATED** — not resume-safe as “stable capacity”)
2. **Highest stable QPS:** **~17** (20 concurrency formal mean **16.99**)
3. **Corresponding concurrency:** **20**
4. **20×3:** mean **16.99**, min **16.68**, max **17.45**
5. **P95 / P99 (20×3 mean):** **~1780 ms / ~2085 ms**
6. **System error rate:** **0%** at 20 and 50 (HTTP); 50 fails on autopay reject / drain
7. **Hikari:** timeoutΔ=**0**; pending peak **10–15** @20, **45** @50
8. **Correctness:** **PASS** (`bad_rows=0`)
9. **AutoPay drain:** **OK @20**; **FAIL @50** (746 WAITING after reject)
10. **Saturation point:** **between 20 and 50 concurrency** under this freeze
11. **Main bottleneck:** shared **Hikari=10** (fully busy) + **hot-SKU row locks** + **autopay queue 200** under sustained multi-payment load
12. **Resume-safe steady QPS:** **≈17 successful reserve QPS @ 20 concurrency** (60s measure, continuous loop, single Docker, Hikari=10, Xmx384m)
13. **Do not claim:** 72 QPS (invalid burst math); 18 QPS @50 as “stable”; “100 concurrency = N QPS”; any untuned higher-concurrency steady number (not measured)

---

## 6. Separated metrics reminder

| Metric | Meaning |
|---|---|
| Concurrency 20 | 20 client threads looping |
| Success QPS ~17 | Completed HTTP 200 reserves / second in 60s window |
| Eventual ISSUED | Async autopay/outbox — drained @20, not @50 |
