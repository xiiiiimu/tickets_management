# PERFORMANCE OPTIMIZATION ROUND 3

**Result dir:** `performance/results/round3-20260918-210920/`  
**Flag:** `PERFORMANCE_ROUND3_SUCCESS=true`  
**Scope:** AUTO PAYMENT delivery only — durable Outbox `AUTO_PAYMENT` → RocketMQ → PaymentConsumer.  
Frozen: Hikari=10, Xmx384m, Docker 768m, JMeter 30s, Lua/CAS/idempotency/SKU model unchanged. No Round 4 / no 100 concurrency.

---

## 1. Before (Round 2 payment delivery)

```
reserve TX commit
  → afterCommit
  → TaskPools.execute("autopay", queue=200)
  → AutoPaymentService.attempt()
```

Steady-state @50: AutoPay reject≈746, WAITING_PAYMENT≈746, cannot drain. Local memory queue was the reliability bottleneck.

## 2. After (Round 3)

```
reserve TX
  → order WAITING_PAYMENT
  → outbox AUTO_PAYMENT (+ RISK_CHECK)
  → commit
  → HTTP returns (still WAITING_PAYMENT OK)

OutboxPublisher (interval 200ms, batch 200, AUTO_PAYMENT prioritized)
  → RocketMQ topic ticket-events
  → RocketTicketListener (AUTO_PAYMENT on MQ thread, no nested Future.get)
  → TicketEventConsumer.consumeAutoPayment (no outer TX across REQUIRES_NEW)
  → AutoPaymentService.attempt / AutoPaymentExecutor
  → PAID + ISSUE outbox
```

## 3. Outbox transactional guarantee

`ReservationTransactionService.reserve` appends `AUTO_PAYMENT` in the **same** `@Transactional` as order insert. Rollback leaves no payment trigger. Crash after commit still has durable outbox row for publisher retry.

## 4. paymentRequestId idempotency

Stable id: `AUTO_PAY:<orderNo>` (`order_no == id` on insert). Payload also carries `paymentRequestId`. Duplicate MQ → same id → payment ledger unique key / prior SUCCESS short-circuit. No random UUID per consume.

## 5. MQ retry semantics

- **System failure** (e.g. DB down in `attempt`): exception propagates from listener → RocketMQ redelivery; consume record not marked DONE.
- **Business / no-op** (order not WAITING_PAYMENT, already paid): attempt returns; consume marked DONE — no infinite retry.
- Demo auto-pay has no balance-fail path; wallet path unchanged.

## 6. Files changed

| File | Change |
|------|--------|
| `ReservationTransactionService.java` | Write `AUTO_PAYMENT` outbox in reserve TX; drop afterCommit autopay |
| `AutoPaymentService.java` | attempt-only; no TaskPools scheduling |
| `OutboxService.java` | AUTO_PAYMENT payload includes `paymentRequestId` |
| `TicketEventConsumer.java` | Handle AUTO_PAYMENT; **no outer TX across REQUIRES_NEW** (Hikari deadlock fix) |
| `RocketTicketListener.java` | AUTO_PAYMENT on MQ thread; RISK_CHECK async (post-trade) |
| `JdbcOutboxRepository.java` | Prefer AUTO_PAYMENT/ISSUE in `findPublishable` |
| `OutboxPublisher.java` | Batch 200 |
| `application.yml` | `jobs.interval-ms: 200` |
| Tests + `performance/run-round3.ps1` | Coverage + steady 20/50 runner |

## 7–8. Tests

- Focused: AutoPayment / DuplicateConsume / Outbox / RiskProfile / Reservation* / OrderState — PASS  
- **full `mvn test`:** PASS (`fail_lines=0`)

## 9–10. Steady-state (same method as PERFORMANCE_STEADY_STATE_REPORT)

| Concurrency | Success QPS | P95 | P99 | Hikari pending peak | Hikari timeout Δ | WAITING final | AUTO_PAYMENT backlog final | Drain s | bad_rows | AutoPay reject Δ | Status |
|-------------|-------------|-----|-----|---------------------|------------------|---------------|----------------------------|---------|----------|------------------|--------|
| **20** | **14.00** | 2390 | 3185 | 11 | **0** | **0** | **0** | 77.9 | 0 | **0** | STABLE |
| **50** | **17.55** | 4518 | 5471 | 42 | **0** | **0** | **0** | 110.5 | 0 | **0** | STABLE |

HTTP: 0×503, 0 SocketTimeout, system error rate 0 at both levels.

## 11–12. Hikari / MySQL

| | 20 | 50 |
|--|----|----|
| active during | 10 | 10 |
| pending peak | 11 | **42** |
| timeout Δ | 0 | 0 |
| acquire max | ~2.0s | ~3.9s |
| row_lock_waits Δ | 1282 | 1683 |
| row_lock_time Δ | 728123 | 774972 |

## 13–14. Outbox / MQ payment

| | 20 | 50 |
|--|----|----|
| AUTO_PAYMENT created | 1207 | 1688 |
| sent | 1207 | 1688 |
| failed/retry final | 0 | 0 |
| backlog peak | 1023 | 1579 |
| backlog final | **0** | **0** |
| mq consumed (counter) | 1376 | 3065 |
| mq duplicate (counter) | 10 | 10 |

## 15. Payment outcomes (post-drain)

| | 20 | 50 |
|--|----|----|
| payment SUCCESS rows | 1207 | 1688 |
| WAITING final | 0 | 0 |
| PAID+ISSUED | 1207 | 1688 |
| local autopay reject | **0** | **0** |

## 16. Round2 / steady baseline vs Round3 @50

| | Old steady @50 | Round3 @50 |
|--|----------------|------------|
| QPS | ≈18.02 | 17.55 (no claim of throughput gain) |
| P95 | ≈4.5s | 4.52s |
| AutoPay reject | **746** | **0** |
| WAITING / drain | not drained | **WAITING=0, backlog=0, drain 110.5s** |

## 17. PERFORMANCE_ROUND3_SUCCESS

**true** — correctness PASS; 20 stable; 50 no local reject; payment outbox drains within 120s; Hikari timeout 0; HTTP sys err 0; delivery reliability fixed (not QPS uplift).

## 18. Saturation point / next bottleneck

Saturation **behavior changed**: payment delivery is no longer the steady-state failure mode at 50.  
Reserve QPS still plateaus ~14–18 with **high Hikari pending** and **MySQL row lock waits** on hot SKU.

**Evidence for next optimization (HOT SKU DB WRITE / STOCK SHARDING):** yes — after durable async payment, remaining limiter is shared Hikari=10 + single-row SKU CAS contention. Do **not** raise Hikari solely to hide payment delivery (already fixed). Round 4 (if any) = SKU stock sharding — not started here.
