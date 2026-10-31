# Final Post-Optimization Performance Report

**Result directory:** `performance/results/final-20260918-113236/`  
**FINAL_PERFORMANCE_ACCEPTANCE = PASS**  
**Collection only** — no business code / Hikari / pool / JVM / JMeter timeout changes in this run.

---

## 1. Environment

| Item | Value |
|---|---|
| git HEAD | `578005dab2e453e0333c536d4224afca65489382` (`578005d-dirty`) |
| OS | Windows 11 / NT 10.0.26200 |
| CPU | 12th Gen Intel Core i7-1260P (12C / 16T) |
| RAM | 16083 MB (free at start ~2240 MB) |
| Docker | 29.7.2 |
| Platform | `credit_risk-ticket-risk-platform-1`, mem **768m**, image `credit_risk-ticket-platform:perf` |
| JVM | `-Xms128m -Xmx384m -XX:+UseSerialGC` |
| Hikari max | **10** |
| autopay | core=**2**, max=**4**, queue=**200** |
| MySQL | mysql:8.0 @ 127.0.0.1:3307 |
| Redis | redis:7-alpine @ 6379 |
| RocketMQ | 4.9.7 namesrv/broker |
| JMeter | 5.6.3, response timeout **30s** (unchanged) |
| Base URL | http://127.0.0.1:8082 **UP** |

Smoke before suite: health UP; reserve → `WAITING_PAYMENT`; order query → eventual `ISSUED`.

Env matches Round 2 frozen stack (same container memory/heap/Hikari/autopay).

---

## 2. Frozen configuration

| Knob | Value | Changed this run? |
|---|---|---|
| Hikari maximumPoolSize | 10 | No |
| JMeter timeout | 30s | No |
| JVM heap | 384m | No |
| autopay pool | 2/4/200 | No |
| Round1 lock removals | in effect | No code edit |
| Round2 async auto-pay | in effect | No code edit |

---

## 3. Test methodology

- New dir `final-<timestamp>` only (no reuse of baseline/round1/round2 dirs).
- Stock ladder: **20 / 50 / 100 × 3**, serial JMeter, initial stock **20**.
- Each round: wait backlog clear → reset MySQL+Redis → warm SKU → before/during/after Prometheus → JMeter → drain wait → SQL validation.
- Metrics separate **business 409** from **system failure** (503 / SocketTimeout / other).
- `completion_rate = (HTTP 200 + expected business 409 + other 4xx) / requests`.
- Extra: `duplicate_request` (50), `risk_profile_block` (50).
- Runner: `performance/run-final.ps1` (collection script only).

**Clean (stock) definition:**  
`http_200=20`, `business_409=concurrency-20`, `system_errors=0`, `socket_timeout=0`, `http_503=0`, `bad_rows=0`, autopay reject gauge stays 0, backlog drains.

---

## 4. 20×3 raw results

| Run | 200 | 409 | 503 | timeout | RPS | P95 | P99 | orders | remain | bad_rows | clean |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| r1 | 20 | 0 | 0 | 0 | 11.72 | 616 | 624 | 20 | 0 | 0 | Y |
| r2 | 20 | 0 | 0 | 0 | 12.07 | 971 | 974 | 20 | 0 | 0 | Y |
| r3 | 20 | 0 | 0 | 0 | 11.83 | 975 | 980 | 20 | 0 | 0 | Y |

Mean completion **100%**, system error **0%**.

---

## 5. 50×3 raw results

| Run | 200 | 409 | 503 | timeout | RPS | P95 | P99 | orders | remain | bad_rows | clean |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| r1 | 20 | 30 | 0 | 0 | 29.62 | 1760 | 1854 | 20 | 0 | 0 | Y |
| r2 | 20 | 30 | 0 | 0 | 28.25 | 2154 | 2542 | 20 | 0 | 0 | Y |
| r3 | 20 | 30 | 0 | 0 | 29.26 | 1797 | 2262 | 20 | 0 | 0 | Y |

409 = sold-out **business rejection**, not system failure. Completion **100%**.

---

## 6. 100×3 raw results

| Run | 200 | 409 | 503 | timeout | RPS | P95 | P99 | orders | remain | bad_rows | clean |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| r1 | 20 | 80 | 0 | 0 | 63.49 | 2735 | 3015 | 20 | 0 | 0 | Y |
| r2 | 20 | 80 | 0 | 0 | 62.00 | 3464 | 3714 | 20 | 0 | 0 | Y |
| r3 | 20 | 80 | 0 | 0 | 61.09 | 2498 | 2748 | 20 | 0 | 0 | Y |

Exact stock semantics: 20 winners + 80 sold-out. **No SocketTimeout / 503**.

---

## 7. Gradient summary

| Concurrency | Clean Runs | Completion mean | Sys Error mean | Avg RPS | Avg P95 | Avg P99 | Hikari Timeout Σ | Bad Rows pass |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 20 | **3/3** | 100% | 0% | 11.87 | ~854 | ~859 | **0** | 3/3 |
| 50 | **3/3** | 100% | 0% | 29.04 | ~1904 | ~2219 | **0** | 3/3 |
| 100 | **3/3** | 100% | 0% | 62.19 | ~2899 | ~3159 | **0** | 3/3 |

Source: `final_summary.csv`.

---

## 8. Hikari metrics

| Metric | Final (9 stock rounds) |
|---|---|
| timeout_delta per run | **0** all |
| pending_peak | **0** |
| acquire_seconds_max | 20: ≤0.035s; 50: ≤1.02s; 100: ≤**2.46s** |
| connections_max | 10 (unchanged) |

No COUNTER_RESET observed (timeout counter stayed 0).

---

## 9. MySQL lock metrics (sum of per-run deltas)

| Concurrency | lock_waits Δ sum | lock_time Δ sum (ms) |
|---:|---:|---:|
| 20 | 70 | ~11523 |
| 50 | 70 | ~44272 |
| 100 | 71 | ~52421 |

Hot-SKU CAS still serializes winners; no longer coupled to HTTP 30s timeouts.

---

## 10. AutoPay executor metrics

| Metric | Observed |
|---|---|
| pool max | 4 |
| active_peak | 0 (sampled; drain completes fast) |
| queue_peak | **0** |
| queue_final | 0.0 every round |
| completed_delta | **20** per stock round (matches orders) |
| threadpool_reject{autopay} | **0.0** every drain-final |
| payment_auto_success_delta | 20 / round |
| payment_auto_failure_delta | 0 |
| WAITING_PAYMENT after drain | **0** |
| ISSUED after drain | **20** |
| PAYMENT_SUCCESS | **20** |

No infinite backlog. (`autopay_rejected` column in CSV is **NA** where counter metric absent; gauge confirms 0.)

---

## 11. Business correctness (9/9 stock rounds)

| Check | Result |
|---|---|
| negative stock | 0 |
| oversell / orders ≤ 20 | pass (orders=20, remain=0) |
| duplicate requestId reservation | 0 |
| purchase limit | 0 |
| duplicate payment SUCCESS | 0 |
| bad_rows | **0** all rounds |

---

## 12. duplicate_request (50)

- HTTP: 50×200, 0 timeout/503  
- reservation_count=**1**, order_count=**1**, DUP_REQUEST bad_rows=**0**  
- PASS

---

## 13. risk_profile_block (50)

- HTTP: 0×200, 50×409 business BLOCK path, 0 system errors  
- BLOCK_ORDERS=0, BLOCK_RESERVATIONS=0, remaining=total=**100** (unchanged)  
- PASS

---

## 14. Baseline → Round1 → Round2 → Final

| Metric | Baseline | Round1 | Round2 | Final |
|---|---|---|---|---|
| 20 clean runs | 1/3 | 3/3 | 3/3 | **3/3** |
| 50 clean runs | 0/3 (timeout) | 0/3 (timeout) | 3/3 | **3/3** |
| 100 clean runs | 0/3 (timeout) | NA | NA | **3/3** |
| Hikari timeout (suite/run) | large growth | +61 suite | 0 | **0** |
| Hikari acquire max @50 | ~30s | ~30s | ~1.3s | **~1.0s** |
| SocketTimeout @50/100 | dominant | dominant @50 | 0 @50 | **0 @50/100** |
| System 503 | present under pool exhaust | some @50 | 0 | **0** |
| P95 @50 (order of) | ~30s | ~30s | ~2–3s | **~1.8–2.2s** |
| bad_rows | 0 | 0 | 0 | **0** |

Do **not** compare timeout-dominated baseline TPS to Final clean TPS.

---

## 15. Remaining bottleneck

Hot-SKU **MySQL CAS / row lock** serialization under contention (lock waits still scale with concurrency). Pool max=10 is adequate for this stock=20 ladder after async auto-pay; not the primary HTTP failure mode anymore.

---

## 16. Resume-safe conclusions

**Allowed:**

- 「50并发热点库存争抢场景稳定完成」— **YES** (3/3 clean)
- 「100并发热点库存争抢场景稳定完成」— **YES** (3/3 clean: 20×200 + 80×409, 0 timeout/503)
- 「同 requestId 50 并发幂等仅 1 单」
- 「BLOCK 画像不占库存」
- 「Hikari=10 下库存争抢无连接超时」

**Safe numeric snippets (2–3):**

1. **100 并发：20 成功 + 80 业务售罄，0 SocketTimeout / 0×503，3/3**
2. **Hikari timeout_delta = 0；acquire_max @100 ≈ 2.46s（远低于 30s）**
3. **bad_rows = 0（9/9）；最终 ISSUED=20 / WAITING=0**

---

## 17. Limitations

- Single Docker host / single platform instance; not multi-AZ or multi-replica.
- Stock fixed at 20; “100 concurrency stable” means HTTP+correctness under sold-out contention, **not** “100 concurrent paid orders”.
- git working tree dirty (optimization commits not necessarily pushed).
- `during` Prometheus is a single snapshot (~2s after start); pending_peak may under-sample brief spikes (acquire_max still evidence-based).
- Client machine free RAM ~2GB during run; JMeter heap capped.

---

## FINAL_PERFORMANCE_ACCEPTANCE

**PASS**

- 20×3 clean  
- 50×3 clean  
- 100×3 clean  
- timeouts/503 not primary  
- correctness 9/9 + dup + block PASS  
- bad_rows all 0  
- autopay converged, no sustained backlog  
