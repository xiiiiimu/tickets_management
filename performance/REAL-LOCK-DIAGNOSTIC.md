# Low-volume real lock diagnosis

```powershell
cd E:\credit_risk
.\performance\run-real-lock-diagnostic.ps1
python .\performance\analyze_real_lock.py .\performance\results\real-lock-<timestamp-id>
```

Requires JDK21, existing compiled current main classes/test dependency classpath, and **already running** credit-risk-dev-mysql / credit-risk-v2-redis with verified Compose ownership and restart:no. The runner performs no Docker lifecycle operation. It never applies SQL to an existing development schema. A fresh `credit_risk_lockdiag_*` schema, schema-only app account and an atomically claimed empty Redis logicalDB are used. Unique users, IPs, devices, events, sessions, SKU/request IDs and1000 initial stock per cohort prevent exhaustion, reuse and risk-frequency pollution. The project's unchanged schema.sql/data.sql, actual risk rules, Armory READY publication and16 buckets are used.

All Java files here are outside Maven source roots and compile only into .tmp. No production file or dependency changes. The probe agent uses existing test-scoped Byte Buddy1.10.22; it observes the actual executor execute call/Runnable start and existing transaction/connection/SQL methods. Calls and transaction semantics are preserved. No executor or consumer is replaced. The application is the real TicketPlatformApplication with the reservations-v2 profile; no worker, queue, scheduling frequency, TTL, Hikari or JDBC parameter-type workaround is configured. Main-source hashes and runtime settings are retained.

Safety isolation disables only post-lock BackgroundJobs and MQ auto-configuration/publication/listeners; payment gateway/mock auto-payment are already disabled by the existing V2 profile. Actual Outbox writes, risk, queues, intent protocol, Lua, MySQL stock/purchase/reservation/order operations and synchronous abort code remain real. The current locking path does not call Agent. Test-side assertions reject any Agent/payment/consumer invocation instead of returning a fabricated verdict; the Agent key is also empty. This is a **real locking-subchain diagnosis**, not payment/issue/MQ/recovery-job capacity or fault-recovery validation.

## Boundaries

- HTTP202 follows successful ThreadPoolExecutor.execute acceptance. It is not durable admission or a lock promise.
- T1 is the outer POST server Filter start through return, including authentication and response processing. Separate client HTTP latency/QPS uses only cohort POSTs; admin polling and idempotency replays are not included in it.
- T2 is the successful execute invocation start through actual Work.run entry. It includes execute/dispatch/thread-start overhead. Initial core tasks can start directly without entering ArrayBlockingQueue; this is not claimed to be an exact internal queue.offer timestamp.
- T3 is Work.run entry through successful ReservationV2Transactions.reserve return after commit. It includes risk, durable scheduling and actual sale execution. Durable queued_at→claimed_at SQL timestamps and decision-commit→sale-process intervals are separate submetrics.
- Lock end requires jobRESERVED, intentCOMPLETED, reservationHELD and orderWAITING_PAYMENT after final commit. Redis Lua success alone is insufficient. Payment/issue are later steps.
- End-to-end per request is T2+T3 from executor acceptance to that reliable lock end. Batch completion is first accepted request→last reliable lock end. TPS uses successful locks divided by this batch window. Low-volume TPS is not capacity. Quantiles of separate stages must not be added.
- Lua timing is the Spring RedisScript call round trip; reserveRuntime additionally reads expiration. Neither is pure Redis-server execution CPU. TransactionTemplate duration includes connection begin, SQL/locks, Redis inside the transaction, commit and cleanup. Per-request pure InnoDB row-lock wait duration is not directly measured.

## Observation and checks

Agent timelines associate HTTP/ingress/sale threads by requestId. Memory-only admin snapshots expose real ingress/sale/Hikari state and GC. Queue/thread maxima are **sampling lower bounds**. One persistent root diagnostic connection samples performance_schema.data_lock_waits every500ms, filtering only the owned schema. It does not enable/reset any server instrumentation. SQL client wall time includes network/scheduling/lock waits and is not equated to pure row-lock wait. Nested spans overlap.

Cohorts are1@1,10@2,30@5,50@10. Each has new pristine business data; no artificial PASS or disabled risk rule is used. Each cohort must reach terminal outcomes within40seconds after sends; startup has70seconds and owned JVM300seconds. No long load or full regression runs. Any correctness/terminal discrepancy stops further cohorts and retains evidence. Resource observations stop further cohorts if host available memory falls below256MiB. Every cohort verifies equal Redis/MySQL16-bucket remaining counts, stock decrease equal to successes, matching held reservations/orders/allocations/purchase counts, zero unresolved intents, no forbidden calls, one work execution per accepted request, and a repeated request that submits/consumes no new work. No failure is injected; successful cases do not prove the DB-failure compensation branch.

After a safe complete run, the owned JVM/observer are stopped, schema/user and exact Redis keys are removed only after ownership-marker verification. No FLUSH or global statistic reset is used. On uncertain failures the namespace is retained for review; credentials are in an ignored .tmp subdirectory, never printed. Failed startup with zero business rows was separately verified and its own empty namespace removed.

Artifacts: configuration/version, raw requests/timelines/spans, all percentiles, per-cohort SQL/Redis stock proofs, idempotency proofs, GC/resources/pool snapshots, filtered MySQL wait samples and cleanup. No production default startup imports this assembly. Stopping use requires no production rollback; simply do not invoke this manual script/agent.
