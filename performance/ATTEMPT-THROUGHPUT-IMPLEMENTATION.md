# Attempt throughput: source audit and implementation boundaries

Status: preparation only. Runtime optimizations, migration and throughput profile are **not implemented**.
No throughput result is claimed. No load, database creation/migration or environment flag change has been executed.

## Confirmed ordering decision

The user explicitly selected **Redis arrival order**, accepting that a request completing qualification earlier may reserve later. This replaces qualification publication order only when `redis-ordered-lane` is enabled. Disabled mode must retain the existing SQL qualification lane.

`ReservationQualificationOrder.publish/requireTurn/passed/aborted` currently couples the durable qualification sequence and `next_lua_seq`. Replacing `requireTurn` alone is unsafe: abort, sold-out sweeping and recovery also advance this cursor. New mode must bypass the old cursor consistently in all four paths. Persist the effective lane protocol and enforce the existing drained-PROCESSING switch barrier on every JVM.

## Actual source boundaries

| Stage | Current code | Constraint on implementation |
|---|---|---|
| HTTP rate control | `ReservationIngress.receive` calls `posts.take()` before durable registration; default `post-rate=1000` | Total HTTP QPS can exceed accepted QPS. Redis gate does not bypass this limiter. Do not claim 5000 successful 202/s with the unchanged single-instance limiter. |
| Receipt | `ReservationV2Service.registerAttempt` performs `queue.byRequest` SQL before readiness, pool check, Armory and `attempts.register` | Redis gate has to move ahead of **this** SQL, not only ahead of Store.register. Retries still require identity/fingerprint verification. |
| Armory | `TicketSaleArmory.runtimePlan/runtimeGate` locks the assembly publication barrier in SQL and validates Redis runtime data | Do not substitute an unversioned local cache. Count fixed Redis round trips separately from eliminating MySQL calls on gate rejection. |
| Registration | `ReservationAttemptStore.register` REQUIRES_NEW, READ_COMMITTED, 3s; dispatch id=1 then job/alias/request checks and counts | Keep SQL authoritative. Batch cannot call register repeatedly: REQUIRES_NEW would produce separate transactions and lock acquisitions. |
| Deadline | Store `renew` uses fresh database time after candidate lock and conditional `waiting_deadline > now` | A batch-wide timestamp is insufficient. Expired candidate must terminalize, never become PROCESSING. |
| Worker | `ReservationV2Service.dispatchAttempts`: submit worker first, worker calls claim; 4 local slots; Store independently checks persisted active_limit | New batch claiming moves this boundary. Track unstarted grants separately; submission rejection must not erase a started task's token. |
| Current batch claim | `ReservationDispatch.claimBatch` operates EVALUATING/QUEUED/CLAIMED, with PASS weighting | It is not a protocol-3 attempt batch claimant. Do not reuse it as-is. |
| Prepare | Transactions.prepare does Armory, owned-job lock, risk guard preparation, durable intent, qualification publish | Merge ownership verification here without dropping the post-risk lease check. Do not persist an allowed admission after ownership loss. |
| Ordered Lua | Transactions.reserveInOrder uses an additional SQL transaction with Armory, job/intent/security locks and SKU lane | New mode may remove the SKU SQL lane, but not the intent prerequisite, security/version barrier or recoverable hold marker. |
| Final confirmation | Transactions.reserve writes bucket allocations, purchase quota, reservation, order, admission, intent, job RESERVED and Outbox in one transaction | Remains atomic. Lua result unknown must be reconciled from marker/tombstone rather than treated as no stock effect. |

## Counter transition coverage

Current mutation sites that must participate in maintained counts and post-commit gate accounting:

* `ReservationAttemptStore.register`: new WAITING (+waiting), alias/idempotent retries (no change).
* `ReservationAttemptStore.renew`: first grant (-waiting,+active), lease recovery (no count change).
* `ReservationAttemptStore.expire`: WAITING -> QUEUE_TIMEOUT (-waiting).
* `ReservationAttemptStore.decided`: PROCESSING -> REJECTED (-active) only for BLOCK.
* `ReservationV2Transactions.reserve`: PROCESSING -> RESERVED (-active).
* `ReservationV2Transactions.abort`: PROCESSING -> terminal (-active); ambiguous committed RESERVED must be untouched.
* `ReservationSoldOutSweep.sweep`: WAITING/PROCESSING -> SOLD_OUT (decrement exactly the source state).
* `ReservationRuntimeRecovery.failed`: previous active state -> FAILED; caller already owns job and confirmed tombstone.

Heartbeat, defer, qualification publication, Lua-turn finish and active_owner release of an already RESERVED order do **not** decrement counts again. Recovery of a PROCESSING lease does not acquire a new global slot. All updates must roll back with their corresponding state update.

Introducing dispatch locking in terminal paths creates a lock-order concern: today register holds dispatch then waits for a job; terminal paths hold a job first. Adding an unconditional dispatch UPDATE after locking the job can introduce deadlocks. This needs an explicit common lock order or a transaction retry policy that preserves the existing reliable intent/recovery behavior. Merely adding increments/decrements is not an accepted implementation.

## Gate reconciliation protocol requirements

Redis version CAS alone does not make a MySQL snapshot and Redis mutation atomic. Two concrete races need tests:

1. A PENDING gate reservation has no MySQL row yet. Replacing Redis counts with SQL COUNT loses this reserved slot.
2. SQL terminal commit occurs, reconciliation reads the new count, then the original afterCommit decrement executes. A second decrement undercounts occupancy.

Use token-scoped idempotent accounting, a gate epoch, and an explicit snapshot/rebuild barrier that includes pending registrations. A new epoch must fail closed until its complete state is published. Missing budget/user/count keys must not be interpreted as zero. Unknown commit outcome is not rollback: do not return a slot before SQL outcome is resolved. Gate requests need bounded TTL cleanup without blindly expiring counts independently of their tokens.

Any multi-key gate Lua requires a common Redis hash tag if cluster support is intended. The literal proposed keys have different cluster slots. Preserve all SKU inventory keys in their existing SKU hash slot; do not put global gate counts in inventory scripts.

## Batch registration failure semantics

* Each request needs its own completion result, stable requestId, user/fingerprint and token ownership.
* SQL uniqueness violations must not silently roll back other successful receipts. Use explicit savepoints where supported, and treat deadlock/transaction timeout as whole-batch failure; never continue on an aborted transaction.
* A caller's 1s wait timeout is **not** cancellation or SQL rollback. An eventual committed request must remain discoverable; a timed-out HTTP thread must not return its gate token while the registrar still owns it.
* Shutdown/rejection returns only uncommitted owned tokens. Callback failure after a successful SQL commit must not convert a reliable registration to a false rollback outcome.
* Maximum 16/5ms batching is not a qualification barrier and must not be introduced into worker eligibility ordering.

## Flag combinations and migration

All six runtime flags require explicit off-path regression, not just an all-on test. `batch-register` must remain safe with `redis-gate` off; `batch-claim` must remain safe with maintained counters off; each new lane mode must be compatible with bucket and vector inventory protocols. Mixed JVM effective modes require a persisted compatibility check before workers/registering start.

The planned additive migration must include counts and lane protocol/sequence storage necessary for crash recovery. It has not been authored or applied. Initial counts require an owned migration transaction after old nodes are drained, not zero defaults interpreted as real occupancy. Turning maintained counters off must continue updating any accounting required by concurrently serving compatible nodes, or reject the mixed protocol before accepting work.

`application-throughput.yml` must not be supplied as an apparently runnable profile before these paths and compatibility checks exist. Worker count 16 must not silently raise persisted active_limit=4. With a hard global concurrency of 4, 170 reliable completions/s would require at most about 23.5ms mean occupied-slot time (Little's law); this is a mathematical requirement, **not** a measured prediction. Neither 5000 QPS nor 170 TPS has been established.

## Tools prepared, not executed

* `mysql_commit_latency.py`: explicit `--execute`; localhost only; new UUID schema and ownership marker; single connection, three warm commits, separate commit and UPDATE+COMMIT distributions; raw samples; bounded process and JDBC timeouts; marker-checked cleanup/reconnection. No durability setting changes.
* `k6/steady_state.js` and `k6/run-steady-state.ps1`: explicit execution acknowledgement, 300–600s measured arrival workload, separate warmup, single/multi SKU fixture validation, unique users to avoid alias contamination, real reliable-202 checks, bounded VUs, target/started/completed/dropped/rejected separated, raw summary and effective-server evidence. They do not provision data, change flags, start a server or activate a fast consumer.

The k6 tool does **not yet** collect HELD/QUEUE_TIMEOUT, stage timers or gate reconciliation from the server. These must be supplied by a server-side raw metrics collector and authoritative final invariants before it is an acceptance harness. A 202 counter is not a HELD counter. Do not run it as a completed acceptance suite.

## Required verification before acceptance

L1: gate-token state machine/reconcile races; batch completion/timeout/rollback and callback ownership; SQL counter transition coverage; claim/deadline/token return; ordered Lua bucket/vector/idempotency/tombstone; defaults/off-path configuration.

Isolated correctness IT: two JVMs with independently configured combinations, exact 512 and user boundaries, missing Redis key rebuild, same-request/alias conflicts, submission rejection before/after actual task start, 5s boundary races, actual forced JVM termination at all three requested failpoints, lease recovery and final inventory/order/Outbox invariants. Do not substitute threads within one JVM for this evidence.

No correctness IT or throughput-runtime implementation has been completed for this new task yet. Earlier E/F test results do not validate these proposed protocols.
