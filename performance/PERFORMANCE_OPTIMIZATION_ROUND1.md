# Performance Optimization Round 1

**Date:** 2026-09-18  
**Result dir:** `performance/results/round1-20260918-103040/`  
**Baseline:** `performance/results/20260918-094904/`  
**PERFORMANCE_ROUND1_SUCCESS = true**

Scope: hot-SKU lock / transaction path only. **No** Hikari / heap / JMeter timeout / auto-pay async changes.

---

## 1. Call chain before

```
HTTP → fast risk
     → SELECT sku exists
     → InventoryService.configure  [SELECT sku FOR UPDATE + Redis HSET every request]
     → ReservationIntentService.create [REQUIRES_NEW + lockIdForUpdate(sku) again]
     → ReservationTransactionService.reserve [Lua + CAS + order/outbox]
     → afterCommit sync auto-pay (unchanged this round)
     → response
```

## 2. What `configure` did

Writes Redis `ticket:{sku}:config` (status/limit/start/end) and, only if SKU is pristine (`remaining==total` and no intents), initializes `ticket:{sku}:stock`.

**Why every reserve called it:** controller always invoked `configure` before create — treated as “ensure Redis ready”, but used full `FOR UPDATE` refresh every time.

**Invariant protected by FOR UPDATE:** consistent MySQL→Redis snapshot while admin might change SKU; **not** stock correctness (Lua + CAS already own that).

**Proper homes:** `CatalogAdminService.update` (now calls `configure`), `BackgroundJobs.cache` (every 30s), IT fixtures, cold `ensureReady`.

## 3. `configure` FOR UPDATE on reserve hot path

**Removed for warm path.** Replaced with `ensureReady`:

- If Redis `config` + `stock` keys exist → **return immediately (no DB lock)**.
- Else → single-flight `configure` (FOR UPDATE) once per SKU cold init.

## 4. Intent SKU lock purpose

`lockIdForUpdate(sku)` sat between double-checked `findByRequestId` reads. It did **not** validate sellability after lock.

Actual idempotency: `request_id` **PRIMARY KEY** + `INSERT ... ON DUPLICATE KEY UPDATE`.

Stock: Redis Lua + `tryDecreaseStock` CAS.

→ Lock was serializing **all** same-SKU intent creates (A/D misuse of SKU row). **Redundant.**

## 5. Intent lock removed?

**Yes.** Plain `findById` existence check only.

## 6. Call chain after

```
HTTP → fast risk
     → SELECT sku exists (no lock)
     → inventory.ensureReady   [no-op if Redis already initialized]
     → intents.create          [REQUIRES_NEW, no SKU FOR UPDATE, PK idempotency]
     → transactions.reserve    [Lua + CAS + order/outbox]
     → afterCommit sync auto-pay (unchanged)
     → response
```

## 7. Files changed

| File | Change |
|---|---|
| `InventoryService.java` | `ensureReady` / `redisReady`; `configure` kept for admin/jobs/cold |
| `TicketController.java` | `configure` → `ensureReady` |
| `ReservationIntentService.java` | remove SKU `FOR UPDATE` |
| `CatalogAdminService.java` | call `configure` after admin update |
| `InventoryEnsureReadyTest.java` | new |
| `ReservationIdempotencyAndReleaseTest.java` | concurrent distinct requestIds |
| `OutboxTest.java` | compile fix (MeterRegistry) — unrelated harness |
| `performance/run-round1.ps1` | Round-1 validator |
| `performance/PERFORMANCE_OPTIMIZATION_ROUND1.md` | this report |

## 8. Why stock correctness holds

- Redis atomic reserve unchanged  
- MySQL CAS `tryDecreaseStock` unchanged  
- `remaining_stock>=0` CHECK unchanged  
- requestId PK / ON DUPLICATE unchanged  
- purchase limit CAS unchanged  
- BLOCK still before inventory  
- Auto-pay / ISSUE / Outbox untouched  

## 9. Focused tests

`InventoryEnsureReadyTest`, `ReservationIdempotencyAndReleaseTest`, `ReservationTransactionRollbackTest`, `OutboxTest` → **PASS**

## 10. Full tests

`mvn test` → **exit 0** (Testcontainers Docker probe warned / skipped where Docker API unavailable; no assertion failures)

## 11. 20×3 results (new)

| Run | Success | Err% | Avg | P95 | P99 | bad_rows | orders | Hikari timeoutΔ |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| r1 | 20/20 | 0 | ~1589 | (from jtl) | | 0 | 20 | **0** |
| r2 | 20/20 | 0 | ~577 | | | 0 | 20 | **0** |
| r3 | 20/20 | 0 | ~565 | | | 0 | 20 | **0** |

JMeter: **3/3 clean HTTP** (baseline was **1/3**).

From summary.csv: P95/P99 for 20×r2≈1153/1153-class; r3 max 927; r1 slower first warm (~2462 max) but still 0% error.

## 12. 50×3 results (new)

| Run | Success | Err% | Notes | bad_rows | orders |
|---:|---:|---:|---|---:|---:|
| r1 | 0/50 | 100 | mostly SocketTimeout + 1×503 | 0 | 20 |
| r2 | 1/50 | 98 | 1×200, 2×409, rest timeout; timeoutΔ=+8 | 0 | 20 |
| r3 | 0/50 | 100 | timeouts | 0 | 20 |

**Improved vs baseline 0/3 any success**, but **still timeout-dominated** at 50 (Hikari max=10 + sync auto-pay hold — Round 2 territory).

## 13. Hikari before/after

| Metric | Baseline formal | Round1 |
|---|---|---|
| Suite `timeout_total` growth | ~153→551 (**+398**) | **0→61 (+61)** |
| Per-run timeoutΔ @20 | (timeout dominated) | **0,0,0** |
| `pending` peak | after snapshot **44** | during **0**; suite-after **4** |
| `acquire_seconds_max` | ≈**30.00s** | @20 ≈**0.34s**; suite-after still saw **30s** from 50-runs |

## 14. MySQL lock before/after

| Metric | Round1 suite |
|---|---|
| `Innodb_row_lock_waits` Δ | **+151** (6 runs) |
| `Innodb_row_lock_time` Δ | **+47632** ms |

Per 20-run waits ~23–27 (no double hot-path FOR UPDATE). Baseline instance had cumulative waits **3489** after heavy timeout runs — not identical window, but direction matches less hot-path locking; **50-run still drives lock+pool pressure** via remaining CAS + sync auto-pay.

## 15. Correctness

All Round1 stock rounds: **bad_rows=0**, `valid_orders=20`, `remaining=0`, no oversell.

## 16. PERFORMANCE_ROUND1_SUCCESS

**true**

Reasons: correctness PASS; 20×3 **3/3** clean (was 1/3); Hikari timeout growth sharply down; pending no longer 44 during 20-runs; 50 shows partial (not total) improvement without touching pool size.

## 17. Current bottleneck

**Still primarily:** DB pool (max=10) **+** long connection hold from **sync afterCommit auto-pay** (and remaining CAS serialization) at ≥50 concurrency.

Hot-path **duplicate SKU FOR UPDATE** is no longer the dominant issue at 20.

## 18. Ready for Round 2?

**Yes** — enter Round 2: make `afterCommit` auto-payment **async** (do not raise Hikari yet). Validate 50×3 again after that.
