# Full Success Path Capacity Benchmark

**Result directory (formal):** `performance/results/full-success-20260918-121442/`  
**FULL_SUCCESS_PERFORMANCE_ACCEPTANCE = PASS**  
**Collection only** — no business code / Hikari / pool / JVM / JMeter / Lua changes.

Invalid / contaminated prior attempts (not for resume numbers):

| Dir | Reason |
|---|---|
| `full-success-20260918-120141` | Fixture bug: Ensure-Sku used wrong column `title`; SKU never created → all 409 |
| `full-success-20260918-120855` | Users CSV included BLOCK-profile user `90100` → exactly 1×409 @100 |

---

## 1. Environment

Same as FINAL (`final-20260918-113236`):

| Item | Value |
|---|---|
| Hikari max | **10** |
| JVM | `-Xms128m -Xmx384m -XX:+UseSerialGC` |
| Docker mem | **768m** |
| autopay | core **2** / max **4** / queue **200** |
| JMeter timeout | **30s** |
| SKU | `perf_sku_risk`, **initial_stock=200**, purchase_limit=10 |
| Users | 90001–90120 **excluding 90100** (BLOCK fixture) |

`ENV_MATCH_FINAL` confirmed before run.

---

## 2. Test methodology

- New dir `full-success-<timestamp>` (does not modify FINAL results).
- Ladder **20 / 50 / 100 × 3**, serial JMeter, unique `requestId` + distinct users.
- Each round: drain clear → reset DB/Redis → warm (keep Redis config) → stock=200 → JMeter → drain → SQL correctness.
- Clean requires: all HTTP 200, 0 timeout/503, Hikari timeoutΔ=0, autopay reject=0, all orders PAID/ISSUED, queue=0, bad_rows=0.

---

## 3. Why stock=200

Hot-contention FINAL uses stock=20 to prove sold-out / anti-oversell.  
This benchmark needs **every** concurrent request to enter the full success path (reserve → CAS → order → async auto-pay → ISSUED), so stock ≥ concurrency with margin (**200**).

---

## 4. 20×3 raw results

| Run | 200/409/503/timeout | overall QPS | success QPS | P95 | P99 | issued | remain | bad | clean |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---|
| r1 | 20/0/0/0 | 11.98 | 11.98 | 681 | 784 | 20 | 180 | 0 | Y |
| r2 | 20/0/0/0 | 12.11 | 12.11 | 3415 | 4091 | 20 | 180 | 0 | Y |
| r3 | 20/0/0/0 | 11.97 | 11.97 | 875 | 881 | 20 | 180 | 0 | Y |

---

## 5. 50×3 raw results

| Run | 200/409/503/timeout | overall QPS | success QPS | P95 | P99 | issued | remain | bad | clean |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---|
| r1 | 50/0/0/0 | 31.57 | 31.57 | 4313 | 4648 | 50 | 150 | 0 | Y |
| r2 | 50/0/0/0 | 31.11 | 31.11 | 4045 | 4168 | 50 | 150 | 0 | Y |
| r3 | 50/0/0/0 | 28.84 | 28.84 | 4471 | 4712 | 50 | 150 | 0 | Y |

---

## 6. 100×3 raw results

| Run | 200/409/503/timeout | overall QPS | success QPS | P95 | P99 | issued | remain | bad | clean |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---|
| r1 | 100/0/0/0 | 60.46 | 60.46 | 6711 | 6876 | 100 | 100 | 0 | Y |
| r2 | 100/0/0/0 | 93.72 | 93.72 | 6654 | 7399 | 100 | 100 | 0 | Y |
| r3 | 100/0/0/0 | 62.11 | 62.11 | 6813 | 7048 | 100 | 100 | 0 | Y |

**100/100 HTTP 200** and **100/100 eventual ISSUED** on all three runs.

---

## 7. QPS breakdown

| Concurrency | clean | avg overall QPS | avg successful-reserve QPS | avg P95 | avg P99 |
|---:|---:|---:|---:|---:|---:|
| 20 | 3/3 | **12.02** | **12.02** | ~1657 | ~1919 |
| 50 | 3/3 | **30.51** | **30.51** | ~4276 | ~4509 |
| 100 | 3/3 | **72.10** | **72.10** | ~6726 | ~7108 |

(overall ≈ successful-reserve because all HTTP 200)

---

## 8. Hikari

| Metric | Value |
|---|---|
| timeout_delta | **0** all 9 runs |
| pending_peak | **0** |
| acquire_max | 20: ≤4.96s; 50: ≤3.14s; 100: ≤**5.39s** |

---

## 9. MySQL lock

| Concurrency | lock_waits Δ sum | lock_time Δ sum (ms) |
|---:|---:|---:|
| 20 | 68 | ~29.5k |
| 50 | 159 | ~120k |
| 100 | 324 | ~175k |

Full-success path drives more lock time than sold-out-heavy hot contention (every request mutates stock).

---

## 10. AutoPay

| Metric | Value |
|---|---|
| queue_peak | **0** |
| rejected | **0** |
| completed_delta | = concurrency each round |
| payment_auto_success_delta | = concurrency |
| payment_auto_failure_delta | 0 |
| queue_final | 0.0 |
| WAITING_PAYMENT final | 0 |

---

## 11. Final order / payment / issue

Every clean round: `order_count = paid_or_issued = payment_success = concurrency`, `issued_orders = concurrency`, `remaining_stock = 200 - concurrency`.

---

## 12. Correctness

All 9 rounds: negative stock / oversell / dup request / dup order / dup payment / purchase limit / invalid state / bad_rows = **0**.

---

## 13. Clean run summary

| Concurrency | Clean |
|---:|---|
| 20 | **3/3** |
| 50 | **3/3** |
| 100 | **3/3** |

---

## 14. Comparison with hot-contention FINAL

| | Hot contention FINAL | Full success (this) |
|---|---|---|
| Stock | 20 | **200** |
| 100 concurrency HTTP | 20×200 + 80×409 | **100×200** |
| Proves | sold-out + anti-oversell under scramble | full path capacity under ample stock |
| Avg success QPS @100 | n/a (mostly business reject) | **~72** |
| Autopay drain | 20 payments | **100 payments** / round |

Do **not** merge into one resume number.

---

## 15. Resume-safe conclusions

**Allowed:**

- 「100并发完整成功链路稳定完成」— **YES** (3/3: 100 HTTP 200 → 100 ISSUED)
- 「成功抢票 QPS ≈ 72」— **YES**, with qualifier: single Docker instance, Hikari=10, Xmx384m, stock=200, JMeter 30s, local Windows host

**Not allowed:**

- Claiming 72 QPS from the stock=20 sold-out test
- Claiming production multi-node capacity from this single-box run

---

## FULL_SUCCESS_PERFORMANCE_ACCEPTANCE

**PASS**
