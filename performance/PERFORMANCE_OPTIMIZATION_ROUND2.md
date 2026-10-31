# Performance Optimization Round 2

**Date:** 2026-09-18  
**Result dir:** `performance/results/round2-20260918-105337/`  
**Round 1 compare:** `performance/results/round1-20260918-103040/`  
**PERFORMANCE_ROUND2_SUCCESS = true**

Scope: **async auto-pay execution mode only**. Hikari max remains **10**. No Lua/CAS/idempotency/pool-size/JMeter/heap changes. Round 1 lock removals kept.

---

## 1. Call chain before (Round 1 / baseline)

```
HTTP Tomcat thread
→ ReservationTransactionService.reserve  [@Transactional]
→ commit
→ TransactionSynchronization.afterCommit   ← same Tomcat thread
   → AutoPaymentService.attempt(orderId)
      → AutoPaymentExecutor.execute        [@Transactional REQUIRES_NEW]
         → lock order FOR UPDATE
         → PAYING → PAID
         → insert payment (paymentRequestId = AUTO_PAY:<orderNo>)
         → outbox ISSUE
→ HTTP response returns
```

**Evidence (code):** Spring `afterCommit` runs on the committing thread; `scheduleAfterCommit` previously called `attempt()` inline in that callback. `attempt` → `executor.execute` opens a **new** TX and re-acquires a Hikari connection while the Tomcat worker is still blocked.

## 2. Call chain after (Round 2)

```
HTTP Tomcat thread
→ reserve TX → commit
→ afterCommit (same Tomcat thread, but light):
   → TaskPools.execute("autopay", task)   // submit only
→ HTTP response returns

autopay-* worker:
→ AutoPaymentService.attempt(orderId)
→ AutoPaymentExecutor.execute (REQUIRES_NEW)
→ payment / order PAID / ISSUE outbox
```

## 3. Async executor design

Reuses `TaskPools` with a new named pool `autopay`:

| Setting | Value |
|---|---|
| corePoolSize | 2 |
| maxPoolSize | 4 |
| queueCapacity | 200 |
| rejectionPolicy | throw `RejectedExecutionException` (TaskPools handler) |
| threadNamePrefix | `autopay-` |
| config | `ticketing.pools.autopay` in `application.yml` |

No `new Thread()`, no `ForkJoinPool.commonPool()`, no unbounded queue, no per-request executor.

## 4. Response semantic

`ReservationTransactionService.response()` reads order status **inside** the reserve TX (before commit), so JSON `status` was already typically `WAITING_PAYMENT` even under sync auto-pay. Round 2 does **not** wait on the future; clients should poll `GET /api/orders/{id}` (exists) for eventual `PAID`/`ISSUED`.

## 5. Failure / rejection / recovery

| Case | Behavior |
|---|---|
| Async `attempt` throws | Logged + `payment.auto.failure`; order stays `WAITING_PAYMENT`; **reserve/order commit not rolled back** |
| Duplicate async / replay | `paymentRequestId=AUTO_PAY:<orderNo>` UNIQUE + executor prior-row short-circuit → one SUCCESS payment |
| Executor rejection | Catch `RejectedExecutionException` → `payment.auto.rejected` + ERROR log; order left `WAITING_PAYMENT` for expire/recovery (**not silent**) |
| Recovery | Existing expire / outbox / state machine; no new silent drop |

Round 2 measured: `payment_auto_failure_total=0`, `threadpool_reject{autopay}=0`, no `payment_auto_rejected_total` samples (counter never created → 0 rejects).

## 6. Files changed

| File | Change |
|---|---|
| `AutoPaymentService.java` | afterCommit → `enqueue` to `autopay` pool; rejection metric/log |
| `TaskPools.java` | register `autopay` pool |
| `application.yml` | `ticketing.pools.autopay: {core:2,max:4,queue:200}` |
| `AutoPaymentServiceTest.java` | async submit / no-wait / idempotent / failure / rejection tests |
| `RiskProfileSemanticTest.java` | ctor + sync mock pool |
| `performance/run-round2.ps1` | 20×3+50×3 + drain wait + autopay metrics |
| `performance/PERFORMANCE_OPTIMIZATION_ROUND2.md` | this report |

## 7. Focused tests

`AutoPaymentServiceTest`, payment/idempotency/reservation correctness set → **PASS**

## 8. Full tests

`mvn test` → **exit 0**

## 9. 20×3 results

| Run | Success | Biz/Sys | P95 | P99 | HTTP timeout | bad_rows | orders | Hikari timeoutΔ | acquire_max |
|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|
| r1 | 20/20 | 0/0 | 4423 | 5088 | 0 | 0 | 20 | **0** | 1.25s |
| r2 | 20/20 | 0/0 | 1660 | 1840 | 0 | 0 | 20 | **0** | 1.25s |
| r3 | 20/20 | 0/0 | 1083 | 1346 | 0 | 0 | 20 | **0** | 0.05s |

**Clean HTTP runs: 3/3** (same as Round 1).

## 10. 50×3 results

| Run | Success | Biz 409 | Sys | P95 | P99 | HTTP timeout | bad_rows | orders | Hikari timeoutΔ | acquire_max |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| r1 | 20 | 30 | **0** | 2752 | 3011 | **0** | 0 | 20 | **0** | 1.26s |
| r2 | 20 | 30 | **0** | 2360 | 2537 | **0** | 0 | 20 | **0** | 1.26s |
| r3 | 20 | 30 | **0** | 3009 | 3062 | **0** | 0 | 20 | **0** | 1.02s |

Round 1 @50 was **timeout-dominated** (~30s SocketTimeout, 0–1 success).  
Round 2 @50: **0 SocketTimeout**, exact stock semantics (20×200 + 30×409), **3/3 usable clean performance samples**.

## 11. Hikari

| Metric | Round1 | Round2 |
|---|---|---|
| Per-run timeoutΔ (all 6) | 0/0/0 then messy @50 (+8 / COUNTER_RESET) | **0/0/0/0/0/0** |
| Suite timeoutΔ | +61 (0→61) | **0** (stayed 0) |
| pending during | 0 (20); pressure @50 | **0.0** all runs |
| acquire_seconds_max | @20 ~0.34s; @50 ~**30s** | @20 ~0.05–1.25s; @50 ~**1.02–1.26s** |
| max pool | 10 | **10** (unchanged) |

## 12. MySQL locks (suite)

| Metric | Round1 | Round2 |
|---|---|---|
| row_lock_waits Δ | +151 | +145 |
| row_lock_time Δ | +47632 ms | +101329 ms |

Lock waits similar; time higher but **no longer coupled to 30s HTTP timeouts** (HTTP finishes; CAS still serializes hot SKU).

## 13. Auto-pay executor metrics

| Metric | Observed |
|---|---|
| queue peak (per-run drain) | **0** (drained within wait window) |
| queue final | 0.0 every run |
| completed | 21→42→63→84→105→126 (+21/run = warm+20) |
| rejected | **0** |
| payment_auto_success | 126 |
| payment_auto_failure | 0 |
| eventual PAID/ISSUED (last sku) | **20** |
| WAITING_PAYMENT backlog after drain | **0** |

Not a fake HTTP-only win: autopay backlog cleared; payments completed.

## 14. Eventual payment correctness

- `DUP_PAYMENT_SUCCESS` bad_rows = 0 every run  
- `payment_auto_success` matches completed autopay tasks  
- Order query API already exists: `GET /api/orders/{id}`  

## 15. Round1 vs Round2

| Metric | Round1 | Round2 | Delta |
|---|---|---|---|
| 20 clean runs | 3/3 | 3/3 | 0 |
| 50 clean (no sys/timeout) | **0/3** | **3/3** | **+3** |
| 20 P95 (best/mid) | ~833–1127 | ~1083–1660 | similar |
| 50 P95 | ~30221–30276 | **~2360–3009** | **~−27s** |
| HTTP timeout count @50 | ~47–50/run | **0** | **−all** |
| Hikari timeout_total Δ (suite) | +61 | **0** | −61 |
| Hikari pending peak | pressure @50 | **0** | down |
| Hikari acquire max @50 | ~30s | **~1.26s** | **~−29s** |
| MySQL lock waits (suite) | +151 | +145 | ≈ |
| MySQL lock time (suite) | +47632 | +101329 | higher (see §12) |
| autopay queue peak | n/a (sync) | **0** | drained |
| autopay rejected | n/a | **0** | — |
| bad_rows | 0 | 0 | 0 |

## 16. PERFORMANCE_ROUND2_SUCCESS

**true**

1. Correctness PASS (`bad_rows=0`, full `mvn test` OK)  
2. 20×3 still clean  
3. 50 no longer 30s-timeout dominated — **3/3 usable samples**  
4. Hikari timeoutΔ ≪ Round1 +61 (now 0)  
5. acquire_max @50 ≪ 30s  
6. pending 0  
7. autopay no infinite backlog  
8. payments eventually correct  

## 17. Remaining bottleneck / Round 3?

At 50 with stock=20, HTTP is healthy. Remaining pressure is **hot-SKU CAS serialization** under Hikari **max=10** (still shared with autopay workers + outbox).  

**Evidence for Round 3 (Hikari sizing):** weaker than before Round 2 — timeouts/pending gone at 50. Round 3 still useful if targeting higher concurrency or lower P95 under sustained multi-SKU / longer TX hold, but **do not raise Hikari solely to hide sync auto-pay** (already fixed). Optional next: measure 50 with intentional longer payment work, or proceed carefully to pool sizing with autopay still async.
