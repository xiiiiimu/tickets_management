# 抢票 V3（Redis Lua + Redis Stream）改造方案：阶段 A

日期：2026-10-31。本阶段只读代码、做设计，没有修改生产代码，也没有跑完整压测。

**标记说明**
- **【事实】**：已经从代码或本地实验验证，后面附代码位置或实验文件。
- **【建议】**：设计建议，需要你确认。
- **【推断】**：没有验证的估算或依据文档的说法。

代码位置相对于 `ticket-risk-platform/src/main/java/com/ticketing/`，行号对应当前 E 盘上的版本（已含第一阶段改动）。

---

## 0. 本轮已经完成的两项验证

### 0.1 batch-claim 短 A/B（2 vCPU，50/s，测量 10s，每组跑 2 次）【事实】

数据：`results/v3-plan-20261031/batch-claim-ab-*.json`。

| | A1 | A2 | B1（开 batch-claim） | B2（开 batch-claim） |
|---|---:|---:|---:|---:|
| claim 类持锁事务数 | 430 | 465 | **52** | **51** |
| 每事务发放 | 1 | 1 | 7.3 | 6.2 |
| claim 等锁 mean | 308ms | 304ms | **129ms** | **110ms** |
| register_batch 等锁 mean | 363ms | 339ms | **53ms** | **62ms** |
| InnoDB 行锁等待次数 / 总等待时间 | 493 / 142s | 539 / 151s | **123 / 9.7s** | **104 / 10.1s** |
| POST p50 | 2.75s | 2.66s | 2.04s | 0.91s |
| HELD TPS | 20.6 | 21.3 | 20.1 | 15.3 |
| **202 之后 QUEUE_TIMEOUT** | **0** | **0** | **69** | **52** |

结论：

- batch-claim 把协调锁上的行锁等待时间降了约 93%，但 HELD TPS 没有提升。瓶颈移到了 Worker 处理链和 CPU。
- 还带来一个副作用：登记变快后，入场速度超过了消化速度，已经拿到 202 的请求在 5 秒后超时。这正好说明，**V3 的 waiting 容量必须按"消化速度 × 5 秒"来定**，而不是一个固定值（见 §8.1）。
- 按你的决定，batch-claim 保持默认关闭，不再继续这条优化。

### 0.2 Redis Stream 语义原型（本地 Redis 7.0.15，独立实例，不是项目代码）【事实】

数据：`results/v3-plan-20261031/redis-stream-prototype-results.json`，脚本：`redis_stream_prototype.py`。

| 实验 | 结果 |
|---|---|
| 受理 Lua 并发：20 个线程发 2000 个请求，cap=100 | 恰好受理 100 个，其余 1900 个返回 BUSY，Stream 长度也是 100 |
| 同一 requestId 从 10 个线程同时提交；同一用户用 10 个不同 requestId 提交 | 共 ACCEPTED 2 个（每个 requestId/用户各 1 个），KNOWN 9 个，ALIAS 9 个，Stream 长度 2；同一 requestId 换参数 → MISMATCH |
| 消费者崩溃 | 租约有效期内，别的消费者 START 得到 HELD_BY_OTHER；租约过期后 TAKEOVER；旧 token 的 FINISH 返回 0（被拒）；新 token 的 FINISH 成功，PEL、active、owner 都清干净 |
| 超过 5 秒截止才 START | 返回 TIMEOUT，同时 ACK 并移出 waiting |
| `XTRIM MINID` 精确裁剪到仍在 PEL 里的条目 | 条目被删除，XAUTOCLAIM 在"已删除"列表里报出它的 id → **裁剪不当会直接丢失待处理消息** |
| AOF everysec 下 SIGKILL redis-server 后重启 | 已回复的 500 个受理全部还在，PEL 里的 100 条也在。**只覆盖进程崩溃**（操作系统页缓存还在），不代表主机掉电 |
| 受理 Lua 吞吐（redis-benchmark，50 个连接，与 Redis 同机） | everysec 约 21.8k/s，p50 1.9ms；always 约 15.1k/s，p50 3.0ms（这台云主机的磁盘，只作参考） |

---

## 1. 结论摘要

1. **【建议】V3 的最小改法：MySQL 任务表 `tb_reservation_job` 继续保留，但改为由 Stream 消费者在开始处理时插入**（下文称"开始即插入"），不再由 HTTP 同步写 WAITING，也不再用全局协调锁调度。
   - 原因【事实】：有 10 多处现有业务依赖这张表的行（支付资格、Armory 重建、恢复补偿、租约围栏、风控快审、查询等），见 §13。
   - 有了这一行，`processAttempt` 之后的风控、prepare、库存 Lua、HELD 事务、abort、恢复都可以**原样复用**。
2. **对外的终态只来自 MySQL**：
   - 成功 = 任务行 RESERVED，且 intent 为 COMPLETED、订单已提交。
   - 失败 = 任务行已经是终态失败（REJECTED、FAILED、EXPIRED、SOLD_OUT、QUEUE_TIMEOUT），或 intent 为 FAILED。
   - Redis 里的状态只用来显示 PROCESSING 或做缓存。查不到时返回 UNKNOWN，提示用同一 requestId 重试。
3. **成功和失败互斥，靠的是已有的 MySQL 机制**【事实】：
   - `tb_reservation_job.request_id` 唯一；
   - `tb_reserve_request.request_id` 是主键；
   - HELD 事务里先 `FOR UPDATE` 读 intent，并要求它是 REQUESTED；
   - `tryMarkFailedIfRequested` 只在 REQUESTED 时才改成 FAILED；
   - 每个事务都用 `lockOwned` 锁住任务行并核对 lease_token。

   V3 只需要再补一件事：**任务行还没插入就已经确定的失败**（排队超时）也要写一行 MySQL 终态，作为围栏（§7）。
4. **库存 Lua 不用改**。V3 新增的 Lua 只用于受理、领取、完成，放在独立的 key 空间里（§4、§5）。
5. 可以接受的丢失范围：Redis 丢了"已受理、还没开始"或"已开始、还没终态"的消息，客户端会看到 UNKNOWN 或 PROCESSING，再用同一 requestId 重试即可。超卖、重复订单、永久占库存和错误终态，都由 MySQL 的唯一约束、库存 CAS、intent CAS 和已有的 120 秒恢复任务兜底（§10）。

---

## 2. 当前真实调用链【事实】

### 2.1 HTTP 受理（protocol 3）

```
ReservationV2Controller.create (ticket/ReservationV2Controller.java:19)
 → ReservationIngress.receive (ticket/ReservationIngress.java:53)
     关闭 → 503；全局令牌桶限流 → 429；usesDurableAttempts → backend.registerAttempt
 → ReservationV2Service.registerAttempt (ticket/ReservationV2Service.java:75)
     intents.fingerprint
     → AttemptAdmissionGate.admit（可选，Redis 建议性闸门，带 Hold/token）
     → queue.byRequest（任务表 ∪ 别名表 UNION 查询，ReservationDispatch.java:46）→ 已有回执就直接返回
     → accepting / readiness / dependenciesAvailable → armory.runtimePlan（读出 sale_version）
     → AttemptRegistrar.register（可选，批量）或 ReservationAttemptStore.register
 → ReservationAttemptStore.registerBatch (ReservationAttemptStore.java:137)
     AttemptCoordinatorLock.acquire("register_batch")：SELECT * FROM tb_reservation_dispatch WHERE id=1 FOR UPDATE
     每条请求：建保存点 → registerLocked (:151)：byRequest；active_owner FOR UPDATE（可能写别名）；
               查 tb_reserve_request；用户在途 COUNT；COUNT WAITING；COUNT active；容量判断；
               INSERT tb_reservation_job(state=WAITING, attempt_protocol=3, waiting_deadline=now+5000,
               reservation_id=Db.id(), order_id=Db.id(), active_owner=user:session)
 → 提交后返回 202，reliableAccepted=true
```

### 2.2 调度（protocol 3）

```
ReservationDispatchScheduler.start(dispatchOnce) / @Scheduled dispatch (ReservationV2Service.java:141)
 → dispatchAttempts (:186)：readiness.ensureReady；soldOutSweep.run；每 5s 给 runningAttempts 续租 heartbeat；
     attempts.expireWaiting()（WAITING 超过截止 → QUEUE_TIMEOUT）；availableHint
 → attempts.claim()（:200，每个 Worker 一次）或 dispatchAttemptBatch → attempts.claimBatch
     AttemptCoordinatorLock.acquire("claim" / "claim_batch")
     claimLocked (ReservationAttemptStore.java:233)：先找过期租约（attempt_protocol=3 AND PROCESSING AND lease_until<=now）
       → COUNT active ≥ active_limit 就返回 → 找 WAITING（attempt_protocol=3，按 created_at 排序）
       → 已过截止就 expire，否则 renew：UPDATE 为 PROCESSING，写 lease_token，lease_until=+15s
 → pools.execute("sale") → process → processAttempt
```

### 2.3 Worker 处理（这一段 V3 要整体复用）

```
processAttempt (ReservationV2Service.java:239)
 1 attempts.currentOwned           事务：job FOR UPDATE，核对 PROCESSING 和 lease_token
 2 风控（admission 为空时）        armory.runtimePlan（版本不一致 → 409）
                                    → risk.classifyReservation 或 quickAdmission.classify
   attempts.decided                事务：job FOR UPDATE + owns；写 audit；
                                    UPDATE decision/admission/state（BLOCK → REJECTED）
 3 attempts.heartbeat
 4 transactions.prepare            attemptTx（REPEATABLE_READ，5s，ReservationV2Transactions.java:47）：
                                    gate(sale_intent_gate)；lockOwned；fences.prepare；
                                    intent 不存在 → insertRequested（ON DUPLICATE KEY no-op），
                                    存在 → assertOwned；qualificationOrder.publish
                                    （Redis 到达顺序模式下直接返回）
 5 transactions.reserve
   ├ reserveInOrder (:107)         attemptTx：gate；lockOwned；intent FOR UPDATE，必须 REQUESTED；
   │                                assertOwned；fences.lockAndCheck；requireTurn（Redis 到达顺序模式 no-op）；
   │                                inventory.reserveRuntime（库存 Lua，在 MySQL 事务和行锁之内调用）；
   │                                qualificationOrder.passed（lua_turn_finished=1）
   └ 最终 HELD 事务 (:69-102)       gate；lockOwned；intent FOR UPDATE，必须 REQUESTED；fences；
                                    loadRuntimeHold（读出 Lua 的结果）；skus.tryDecreaseStockForReservation（MySQL 库存 CAS）；
                                    purchases.ensureZeroRow + tryIncreaseWithinLimit；reservations.insertHeld；
                                    orders.insertWaitingPayment；risk.saveAdmission(job.admission)；
                                    intents.markCompleted；job → RESERVED；outbox.append(RESERVATION_CREATED)
 异常 → abort (:118, ReservationV2Transactions.java:128)：
       lockOwned；intent 为 COMPLETED → 直接返回（绝不补偿已经成功的提交）；
       inventory.abortRuntime（写 ABORTED 墓碑并归还库存）；qualificationOrder.aborted；
       tryMarkFailedIfRequested；audit；job → REJECTED / EXPIRED / SOLD_OUT / FAILED
```

每张票要提交的 MySQL 事务：register（批量）、claim、decided、prepare、ordered-lua、最终 HELD，共 6 个。其中 register 和 claim 两个在全局锁内。

### 2.4 恢复与查询

- **恢复**：`BackgroundJobs.recover`（infrastructure/BackgroundJobs.java:20）每 10 秒查 `tb_reserve_request` 里 REQUESTED 且超过 120 秒的记录，交给 `ReservationRecoveryService.recover`（inventory/ReservationRecoveryService.java）：
  - 任务行存在且租约还有效 → 跳过；
  - 否则：有任务行 → `abortRuntime`（写墓碑）；**没有任务行 → `inventory.release`，release_ticket.lua 只释放 HELD，不写墓碑**；
  - 然后 intent → FAILED；`runtime.failed(job)`。
- **查询**：`GET /by-request` → `ReservationIngress.status`（:80）→ `lookupRequest` → `queue.byRequest`。查不到时，返回 `uncertain(...)`（authoritative=false，terminal=false），本身就不会把"没查到"当成失败。

---

## 3. 库存 Lua（`resources/lua/reserve_ticket_bucket.lua` 等）【事实】

| 项 | 语义 |
|---|---|
| KEYS（`InventoryService.runtimeKeys`，:239） | `ticket:{sku}:config`、16 个库存桶、`user:<uid>`（SKU 级已购数量）、`reservation:<reservationId>`、`pending`（zset）、`assembly-fence`。**全部在 `{sku}` 这个 hash slot 里** |
| ARGV | fingerprint、quantity、reservationId、"0"、userId、优先桶、saleVersion、截止时间（V2 用 sale_end） |
| 幂等 | 预占记录已存在时：fingerprint 不一致 → -4；状态为 RELEASED 或 ABORTED → -5（墓碑，迟到的命令无法复活）；版本不一致 → -7；已是 HELD → 返回 100+bucket（同一个 reservationId 重复执行不会再扣库存） |
| 限购 | `user` 数量 + q > config.limit → -2（MySQL 侧还有 `purchases.tryIncreaseWithinLimit` 再挡一次） |
| 其他返回 | -1 不在售卖期；-3 售罄；-6 数量非法；-8 桶缺失；-10 超过截止时间；HELD 写入 `expiresAt=now+600000` 并加入 pending |
| 补偿 | `abort_reservation_v2.lua`：HELD → 归还库存、扣回 user 数量；**没有 HELD 也会写 ABORTED 墓碑** |
| 释放 | `release_ticket.lua`：只处理 HELD → RELEASED，不写墓碑（用于订单支付超时或取消） |
| 售罄 | `mark_sold_out_attempt.lua`：按每个尝试做零库存线性化，写 ABORTED/SOLD_OUT 墓碑 |

- **能否直接复用：可以，不用改**。V3 的 Worker 仍然通过 `transactions.reserve → reserveInOrder → reserveRuntime` 调用它，幂等 key 仍是任务行上的 reservation_id。
- **需要新增受理 Lua**（§5），但它和库存 Lua 不在同一个 slot。

---

## 4. V3 目标调用链【建议】

```
HTTP POST
 → ReservationIngress.receive（保留：关闭/限流）
 → V3Entry.accept：fingerprint；runtimePlan 取 saleVersion；生成 attemptId / reservationId / orderId（UUID）
 → ACCEPT Lua（{tkv3} slot）：requestId 幂等、同用户同场次别名、用户在途上限、waiting 容量、XADD
 → 202 {state: PROCESSING, durable:false, attemptId}；KNOWN 时返回原状态
     （BUSY/CLOSED → 429/503 未受理；调用超时 → 503 UNKNOWN）

Worker（每个节点一个消费循环 + 现有的 "sale" 线程池）
 → XREADGROUP GROUP v3 <node> COUNT <空闲槽位> BLOCK 1000 STREAMS {tkv3}:stream >
   （另外定期 XAUTOCLAIM，min-idle = 租约时长）
 → START Lua：截止判断 + 全局 active 上限 + Redis 侧租约
 → T0：MySQL "开始即插入" tb_reservation_job(PROCESSING, attempt_protocol=4, lease_token)，或接管已有行
 → processAttempt(job)  ← 原样复用（风控、decided、prepare、reserveInOrder/库存 Lua、HELD 事务、abort）
 → 从 MySQL 读终态 → FINISH Lua（核对 token、XACK+XDEL、释放名额）

查询 GET /by-request
 → MySQL（queue.byRequest）有记录 → 权威结果
 → 否则查 Redis req：WAITING/PROCESSING → PROCESSING；已写围栏的超时 → 失败；ALIAS → 转查目标；
   都没有 → UNKNOWN（用同一 requestId 重试）
```

---

## 5. Redis Key、Stream 字段与状态【建议】

### 5.1 Key 设计

所有受理和调度用的 key 共用一个 hash tag `{tkv3}`，保证 Lua 只访问单个 slot。库存 key 仍在 `{sku}` slot。

| Key | 类型 | 内容 | 清理 |
|---|---|---|---|
| `{tkv3}:cfg` | hash | mode（OPEN/CLOSED）、epoch、waitingCap、activeLimit、userInflight | 永久 |
| `{tkv3}:req:<requestId>` | hash | fp、user、session、attempt、reservation、order、saleVersion、state、deadline、sid、token、lease、aliasOf、fenced、code | TTL = retention（建议 30 分钟，要大于前端轮询窗口） |
| `{tkv3}:owner:<uid>:<session>` | string | 当前活跃尝试的 requestId（同用户同场次的别名） | 终态时删除（只删值等于自己的）；兜底 TTL |
| `{tkv3}:uinf:<uid>` | zset | 该用户在途的 requestId，score = deadline 或 lease | 过期自动剔除；FINISH 时 ZREM |
| `{tkv3}:waiting` | zset | 已受理、还没开始的 requestId，score = deadline | ACCEPT 时剔除已过期的（截止时间一过就不可能再 START，所以剔除是安全的，原型已验证） |
| `{tkv3}:active` | zset | 处理中的 requestId，score = lease_until | FINISH 时 ZREM；接管时只更新 score；由对账器按 MySQL 修正 |
| `{tkv3}:stream` | stream | 待处理消息 | **FINISH 里 XACK + XDEL**，不用 MAXLEN，也不对 PEL 里的条目做 MINID 裁剪（原型已证明会丢消息） |
| 消费者组 `v3` | | 消费者名 = 节点 id | |

### 5.2 Stream 消息字段

`v`（消息格式版本=1）、`requestId`、`attemptId`、`reservationId`、`orderId`、`userId`、`sessionId`、`skuId`、`quantity`、`deviceId`、`ip`、`fingerprint`、`saleVersion`、`deadline`、`acceptedAt`、`traceId`。

这些正好覆盖插入任务行所需的全部 NOT NULL 列【事实：schema.sql:266】。

### 5.3 Redis req 状态（只是缓存，不是权威）

```
（不存在）→ WAITING → PROCESSING → DONE（code 来自 MySQL 终态）
          → ALIAS（aliasOf=目标 requestId）
WAITING → QUEUE_TIMEOUT（fenced=0）→ fenced=1（MySQL 围栏已提交）
```

---

## 6. 关键原子操作与 MySQL 事务边界

### 6.1 Redis Lua【建议，语义已在原型验证】

| 脚本 | 逻辑 |
|---|---|
| **ACCEPT** | mode≠OPEN → CLOSED；req 已存在：fp 不一致 → MISMATCH，否则返回 KNOWN 和原状态；owner 存在 → 写 ALIAS req 后返回别名；剔除过期后 ZCARD(waiting) ≥ cap，或 uinf ≥ 用户上限 → BUSY（**什么都不写**）；否则 `deadline = Redis TIME + 5000`；XADD；HSET req；SET owner；ZADD waiting / uinf |
| **START** | req 不存在 → MISSING（按消息内容走 MySQL 判断，见 §11）；WAITING：now ≥ deadline → QUEUE_TIMEOUT（交给围栏批量写入，§7.2），ZCARD(active) ≥ limit → FULL（不 ACK，稍后重试），否则 → PROCESSING，写 token 和 lease，ZREM waiting，ZADD active；PROCESSING：lease 还有效 → HELD_BY_OTHER，已过期 → TAKEOVER（换新 token）；终态 → XACK + XDEL |
| **RENEW** | token 匹配才延长 lease，并更新 active 的 score；同时用同一消费者 `XCLAIM … JUSTID` 重置 PEL 的空闲时间 |
| **FINISH** | token 不匹配，或状态不是 PROCESSING → 0；否则写入终态 code，ZREM active / uinf，删除值等于自己的 owner，XACK + XDEL |
| **FENCED** | 围栏 MySQL 提交后，把 QUEUE_TIMEOUT 标成 fenced=1，并 XACK + XDEL |

### 6.2 MySQL 事务

| 事务 | 内容 | 复用 / 新增 |
|---|---|---|
| **T0 开始即插入**【新增】 | `INSERT tb_reservation_job(id=attemptId, request_id, …, state='PROCESSING', attempt_protocol=4, lease_token=token, lease_until=now+15s, waiting_deadline=deadline, queue_expires_at=deadline, processing_started_at, claimed_at, active_owner=uid:session, reservation_id, order_id)`。各类冲突的处理见下表 | 新增，复用 registerLocked 里清理 active_owner 的规则（:155-163），需要抽成公共方法 |
| T1 decided | 复用 `attempts.decided`（:273） | 【建议】后续可以和 T0 合并（先算风控再插入），每张票少一次提交 |
| T2 prepare | 复用 | 不变 |
| T3 reserveInOrder（库存 Lua） | 复用 | 不变（要求 Redis 到达顺序模式） |
| T4 HELD | 复用 | 不变 |
| Ta abort | 复用 | 不变 |
| Tf 超时围栏【新增】 | 一次多行 `INSERT tb_reservation_job(... state='QUEUE_TIMEOUT', result_code='QUEUE_TIMEOUT', active_owner=NULL, attempt_protocol=4) ON DUPLICATE KEY UPDATE id=id`，然后回读确认 | 新增 |

T0 遇到冲突时：

| 冲突 | 处理 |
|---|---|
| `request_id` 已存在 | `SELECT … FOR UPDATE`：已是终态 → FINISH(终态)；PROCESSING 且租约有效 → HELD_BY_OTHER；租约已过期 → `UPDATE … SET lease_token=?, lease_until=? WHERE id=? AND state='PROCESSING' AND (lease_until<=now OR lease_token IS NULL)` 接管 |
| `active_owner` 已存在（同用户同场次已有活跃尝试） | 按现有 registerLocked 的逻辑：如果是 RESERVED 且订单已终态 → 清掉 owner 后重插；否则写 `tb_reservation_request_alias`，FINISH 成 ALIAS |

全程没有全局锁：T0 只锁 job 表的行和唯一索引。

---

## 7. 迟到提交的围栏，以及失败终态的持久化

### 7.1 防止旧 Worker 迟到提交（问题 5）

【事实】部分来自现有代码，【建议】部分是 V3 要补的：

1. **MySQL 写入的唯一围栏仍是任务行的 lease_token**。prepare、reserveInOrder、HELD 和 abort 里的每次写入，都先执行 `lockOwned`（`SELECT … FOR UPDATE` + 比较 token，ReservationV2Transactions.java:143）。
   - 【建议】接管时，T0 用 CAS 换新 token，并且只在 **MySQL 租约已过期**时才允许接管。Redis 的租约只决定"谁去尝试接管"，以 MySQL 为准。
2. **库存 Lua 只在 reserveInOrder 事务内、持有任务行锁时才执行**【事实】。接管用的 UPDATE 必须等这把行锁释放，所以旧 Worker 不可能在被接管之后再执行 Lua；如果旧 Worker 已经提交成功，接管方会看到行不再是 PROCESSING，于是放弃接管。
3. Lua 已执行，但事务回滚（lua_turn_finished 没有持久化）：新的处理者重跑时 Lua 是幂等的，返回同一份 HELD；或者 abort 写墓碑归还库存【事实：Lua 幂等和墓碑语义】。
4. Redis 侧：旧 token 的 FINISH 会被拒绝，不会 XACK（原型已验证）。
5. XAUTOCLAIM 把消息转给新消费者时，如果原 Worker 还在处理（租约有效）→ 新消费者 START 得到 HELD_BY_OTHER，不 ACK（原型已验证）；原 Worker 完成时 FINISH 照常 XACK（XACK 只看消费者组和消息 id，不管当前由哪个消费者持有）。

### 7.2 失败终态必须写进 MySQL，并与成功互斥（问题 6）

- **已经插入任务行的失败**【事实】：abort 把行改成终态，并且只在 intent 为 REQUESTED 时才改成 FAILED。HELD 事务要求 intent 是 REQUESTED，而且先锁行。终态行不会再回到 PROCESSING（代码里只有 WAITING→PROCESSING 和 PROCESSING 续租两种转移）。所以一旦失败，之后不可能再生成成功订单。
- **还没插入任务行就确定的失败**（只有排队超时一种）【建议】：
  - 消费者读到消息时判定 TIMEOUT，这时手里有完整字段，就批量执行 Tf，插入 QUEUE_TIMEOUT 终态行；
  - 提交成功后才执行 FENCED 并 XACK；
  - 之后同一个 requestId 即使因为 Redis 丢数据被重新受理，T0 也会因为 request_id 唯一而遇到终态行，不会成功。
  - 围栏写入失败（DB 不可用）→ 不 ACK，查询返回 PROCESSING。
- **受理被拒（BUSY/CLOSED/MISMATCH）**不算"受理后失败"：返回 429/503/409，并说明"本次提交未受理，可以用同一 requestId 重试"。因为 ACCEPT 在这几种情况下什么都不写，所以不存在后续订单。
- **只存在 Redis 里的结论一律不当作权威失败**：没写围栏的 QUEUE_TIMEOUT 对外显示 PROCESSING。

---

## 8. 原有语义在 V3 中的对应（问题 8）

| 语义 | 现状【事实】 | V3【建议】 |
|---|---|---|
| **5 秒 WAITING 截止** | 登记时 MySQL now+5000；claim 时用 MySQL 时间判断；领取成功后就不再看截止时间（`processAttempt` 里有注释 "No queue deadline is consulted after the valid WAITING -> PROCESSING grant"） | ACCEPT 时 Redis TIME+5000；START 在 Lua 里用 Redis TIME 判断；START 成功后同样不再看截止时间（接管也不看），与现有语义一致；waiting zset 在截止时间到了就自动释放名额 |
| **全局 active_limit** | 存在 tb_reservation_dispatch，靠协调锁 + COUNT 保证 | `{tkv3}:active` + START 里原子判断；对账器每隔一段时间用 MySQL 中 protocol 4 的 PROCESSING 行（租约有效）修正 |
| **全局容量** | waiting+active ≤ capacity(512)，且 waiting ≤ 按服务时间预测的预算 | waiting zset ≤ waitingCap。**§0.1 的实测说明固定 cap 会导致 202 之后超时**，所以 cap 应设为 min(配置上限, 消化速率 EWMA × 5s × 0.8)，由对账器发布到 cfg |
| **用户在途** | registerLocked：该用户 WAITING+PROCESSING 数 ≥ per-user-inflight(4) → 拒绝；active_owner 唯一 → 同场次第二次点击变成别名 | Redis 用 uinf zset + owner 别名；MySQL 的 active_owner 唯一约束作为兜底（T0） |
| **用户限购** | Redis Lua（-2）+ MySQL `purchases.tryIncreaseWithinLimit` | 不变 |
| **requestId 幂等** | MySQL：job.request_id 唯一 + 别名表 + intent 主键 | Redis req 负责快速幂等；MySQL 的三个唯一约束是最终屏障 |
| **SKU 内顺序** | Redis 到达顺序模式：谁先到达 Redis Lua 谁先预占 | 同上（V3 只支持 Redis 到达顺序模式，不支持 SQL 游标） |

---

## 9. 新旧链路隔离、灰度与回滚（问题 9）

【事实】现有的隔离点：

- `ReservationAttemptStore.initialize` 要求 `runtime_protocol=3`，否则启动失败（:66）；
- 所有领取和过期扫描 SQL 都带 `attempt_protocol=3` 过滤（:201、:235、:239）；
- `request_id` 在任务表和 intent 表里都唯一。

【事实】**需要修补的地方**：下面这些 COUNT 或查询没有带协议过滤，或者只写死了 3：

| 位置 | 问题 |
|---|---|
| `ReservationAttemptStore` :107-108、:175-176、:237 | COUNT WAITING / active 没有协议过滤 |
| `ReviewQuickCheck` :88 | 冲突检查只查 =3 |
| `TicketController` :28 | 旧入口防护只查 =3 |
| `AttemptWorkerReadiness` :49 | 只查 =3 |
| `ReservationSoldOutSweep` :32 | 只查 =3 |
| `isAttempt` :92 | `==3`，被 V2Transactions 和 abort 的大量分支使用 |

【建议】做法：

1. **全局协议开关**：`tb_reservation_dispatch.runtime_protocol = 4` 表示 V3。
   - V3 启动时要求它为 4，否则失败；V2 的 attempts 组件本来就要求它为 3，两者天然互斥。
   - Redis 的 `cfg.mode` 只能在 runtime_protocol=4 时由 V3 设为 OPEN，并带 epoch。
2. `isAttempt` 扩展为 3 或 4（新增 `isProtocol4` 区分少数分支）；上表里的 COUNT 加 `attempt_protocol=3` 过滤，V3 相关查询改为 IN (3,4)。
3. **切换步骤**：V2 `accepting=false` → 等 protocol 3 的 WAITING=0（约 5 秒）、PROCESSING=0、REQUESTED intent 都处理完 → 把 runtime_protocol 改成 4 → 节点用 V3 配置重启 → cfg 设为 OPEN。
4. **回滚步骤**：cfg 设为 CLOSED（ACCEPT 返回 503，明确未受理）→ 等 waiting 清空（约 5 秒）、PEL 和 active 清空、MySQL protocol 4 的 PROCESSING=0 → 把 runtime_protocol 改回 3 → 重启 V2。历史上 protocol 4 的 RESERVED 订单仍可以支付和出票，因为支付和 Armory 只看任务行和订单，见 §13。
5. **不能有双 Worker**：V2 的领取 SQL 只取 =3 的行；V3 只消费 Stream；同一个 requestId 在 MySQL 里只能有一行任务。
6. **灰度**：不建议在同一场次里按比例分流（两条队列争同一份库存和容量，公平性说不清）。建议先在预发环境整体切换，再在生产上选一场销售开始前切换。按场次灰度需要给场次加协议列，第二期再考虑。

---

## 10. Redis 持久化风险与可接受的故障范围（问题 10）

**【事实】当前部署**（docker-compose.yml）：

- 单实例 `redis:7-alpine`，`--appendonly yes --maxmemory-policy noeviction`；
- 没有设置 appendfsync，即默认 everysec；
- 没有副本或 Sentinel；
- 库存 key 和本次闸门 key 在同一个实例里。

**【事实】本地验证**：SIGKILL 进程后，已回复的写入和 PEL 都保留（只覆盖进程崩溃）。

**【推断，依据 Redis 文档，未在本地验证】**：

- everysec 在主机崩溃或掉电时，大约会丢最近 1 秒（最坏约 2 秒）的写入；
- 主从复制是异步的，故障切换可能丢失已经回复过的写入；
- `WAITAOF`（7.2 以后才有）可以等本地和副本刷盘，但仍不等于共识，选错副本照样会丢；
- 本地是 7.0.15，没法测 WAITAOF。

**在新契约下可以接受的丢失**：

| 丢失的东西 | 后果 | 为什么不违反契约 |
|---|---|---|
| 已受理但还没 START 的消息 | 查询返回 UNKNOWN，客户端用同一 requestId 重试 | 没有 MySQL 记录，也从没对外宣布过终态 |
| START 之后的 Redis 状态 | MySQL 有 PROCESSING 行 | 由孤儿清理（§11）或 120 秒恢复任务定为终态 |
| 别名 / owner 标记 | 重复点击可能再次被受理 | T0 撞上 active_owner 唯一约束，变成别名 |
| waiting / active 计数 | 短时间多放行（偏松） | MySQL 和库存 CAS 仍然生效，对账器修正 |

**需要单独关注的风险（不是 V3 新引入的）**：Redis 库存 key 丢失后 Redis 显示的库存偏高。由 MySQL 的 `tryDecreaseStockForReservation` CAS 防超卖【事实】，再由 Armory 重建对账。

【建议】给 V3 的受理和调度单独部署一个 Redis 实例，避免和库存 Redis 互相影响。同时确认是否把库存 Redis 改成 `appendfsync always`（这不在 V3 范围内，需要你决定）。

---

## 11. 各场景的处理流程

| 场景 | 处理 | 对外状态 |
|---|---|---|
| **正常** | ACCEPT → XADD → START → T0 → 风控 → T1 → T2 → T3(Lua) → T4(HELD) → FINISH | POST 202 PROCESSING；查询 RESERVED = 成功 |
| **重复点击，同一 requestId** | ACCEPT 返回 KNOWN 和原状态；Redis 丢了数据后再次受理 → T0 撞 request_id 唯一 → 沿用已有行 | 同一个结果 |
| **重复点击，新 requestId** | owner 存在 → ALIAS；owner 丢了 → T0 撞 active_owner → 写别名行（与现状一致） | 指向同一个尝试 |
| **响应丢失 / ACCEPT 调用超时** | 客户端用同一 requestId 重试，得到 KNOWN；或者查询 | PROCESSING 或 UNKNOWN |
| **消息丢失（Redis 数据丢失）** | 还没 START：没有 MySQL 记录 → UNKNOWN → 重试会重新受理；已 START：MySQL PROCESSING 行由孤儿清理定终态 | UNKNOWN → 重试；或之后在 MySQL 里成为失败终态 |
| **消费者崩溃** | 任务行租约 15 秒到期 → XAUTOCLAIM → START TAKEOVER → T0 接管（换 token）→ `processAttempt` 从断点继续（admission、intent、lua_turn_finished 都已持久化，各步骤幂等） | PROCESSING |
| **5 秒内没开始** | START 返回 TIMEOUT → Tf 批量写围栏 → FENCED | 写围栏前 PROCESSING，写完后是失败终态 |
| **active 已满** | FULL，不 ACK，稍后重新读自己的 PEL；超过截止时间就走超时 | PROCESSING |
| **MySQL 提交结果未知（T4）** | 现有逻辑：catch 后 abort → 回读 intent；COMPLETED → 不补偿，行是 RESERVED；仍是 REQUESTED（提交失败）→ abortRuntime + FAILED；DB 不可达 → abort 也失败，行保留 PROCESSING，不 FINISH，消息留在 PEL，等租约过期后接管或恢复 | PROCESSING，直到 MySQL 有结论 |
| **Redis 重启（AOF 完整）** | PEL、req、zset 都恢复（已验证）；原来持有消息的消费者如果已经不在，消息会因空闲超时被 XAUTOCLAIM 接走 | 不受影响 |
| **Redis 重启（丢了约 1 秒）或数据全部丢失** | 同"消息丢失"；对账器用 MySQL 重建 active；mode/cfg 丢失时 ACCEPT 返回 CLOSED，等运维确认后再打开（epoch 变化） | UNKNOWN，然后重试 |
| **库存 Lua 已执行、MySQL 失败** | 现有 abort → abortRuntime 写墓碑并归还库存；DB 不可用时，intent 保持 REQUESTED，超过 120 秒由恢复任务 abortRuntime | 最终是失败终态 |
| **旧 Worker 迟到** | MySQL 写入被 lockOwned 拒绝，Lua 被行锁挡住，FINISH 被 token 拒绝（§7.1） | 不影响 |

**孤儿清理【新增】**：protocol 4 的 PROCESSING 行，租约过期超过 N 秒、而且 Stream 里没有对应消息 →

- intent 是 REQUESTED：交给现有的恢复任务；
- 还没有 intent：直接走 `transactions.abort`（会写终态并清理 owner）。

**MISSING（消息还在、req 没了）**：按消息里的 deadline 判断：已过期 → 走超时围栏；没过期 → 用消息字段补建 req 后继续处理。

---

## 12. 类、方法和表的清单

### 保留并原样复用【事实：这些都存在】
- `ReservationV2Transactions.prepare / reserve / reserveInOrder / abort`
- `InventoryService.reserveRuntime / loadRuntimeHold / abortRuntime / hasRuntimeHold / release` 及全部库存 Lua
- `ReservationQualificationOrder`（Redis 到达顺序模式）
- `RiskDecisionService.classifyReservation`、`AttemptRiskAdmission` / `ReviewQuickCheck`
- `RiskSubjectFence`
- `ReservationIntentService` / `JdbcReservationIntentRepository`
- `OrderService`、`OutboxService`、支付和出票、`TicketEventConsumer`
- `BackgroundJobs.recover` + `ReservationRecoveryService` + `ReservationRuntimeRecovery`
- `ReservationAttemptStore.decided / currentOwned / heartbeat / defer`
- `ReservationIngress` 的关闭、限流和 `uncertain` 响应
- 表：tb_reserve_request、tb_reservation、tb_ticket_order、tb_user_purchase、tb_stock_bucket_allocation(_item)、tb_outbox_message、tb_reservation_job（角色改变）、tb_reservation_request_alias、tb_reservation_risk_audit、tb_reservation_dispatch（只保留协议开关）

### 需要修改
| 位置 | 修改 |
|---|---|
| `ReservationAttemptStore.isAttempt` | 改为 3 或 4 |
| 各处 COUNT / 查询（§9 的表） | 协议过滤 |
| `ReservationAttemptStore.initialize` | 拆分：V3 不需要 V2 登记相关的检查；租约相关方法要能在 protocol 4 下使用（建议抽出 `AttemptLeaseOps`） |
| `ReservationV2Service.registerAttempt` / `dispatchOnce` | 增加 V3 分支，或改由新类接管 |
| `ReservationIngress.status` | MySQL 查不到时，回退查 Redis |
| `ReservationRecoveryService` | 不变（protocol 4 有任务行，会走 abortRuntime） |
| active_owner 清理逻辑（registerLocked :155-163） | 抽成公共方法给 T0 使用 |

### 新增【建议】
`V3AcceptScripts`（ACCEPT / START / RENEW / FINISH / FENCED）、`V3Entry`、`V3StreamConsumer`（XREADGROUP / XAUTOCLAIM / 续租 / FINISH）、`V3JobStarter`（T0 和接管）、`V3TimeoutFence`（Tf）、`V3Reconciler`（active、waitingCap、孤儿清理）、`V3ModeGuard`（runtime_protocol=4、cfg epoch），以及迁移脚本（只改 runtime_protocol 的约束说明，表结构不需要改）。

### V3 下退役（V2 代码保留，用于回滚）
`AttemptRegistrar`、`AttemptAdmissionGate`、`AttemptCoordinatorLock` 及 `register / registerBatch / claim / claimBatch / expireWaiting / availableHint / admissionEstimate`、`AttemptAdmissionBudget`（waiting 预算）、`ReservationDispatchScheduler` 的 attempts 调度、protocol 4 下的 `ReservationSoldOutSweep`（售罄改由消费者执行库存 Lua 时自然判定）、`tb_reservation_sku_order`（Redis 到达顺序模式本来就不用它）。

---

## 13. 阻碍移除 MySQL 任务表的现有依赖【事实】

| 依赖 | 位置 | 依赖的内容 |
|---|---|---|
| 支付资格 | `payment/ReservationPaymentPolicy.java:18, 28-33` | `isV2` 按 order_id 查任务行；`assertQualified` 要求 job.decision 为 PASS/REVIEW 且 state=RESERVED |
| Armory 重建 | `sale/TicketSaleArmory.java:436` | 任务行为 RESERVED 时，重建 protocolVersion=2 的预占标记 |
| 租约围栏 | `ReservationV2Transactions.lockOwned`（:143）及所有事务 | 任务行 + lease_token |
| 风控结果 | `decided` 写入 admission JSON，HELD 事务里 `risk.saveAdmission` 读取 | 任务行的 admission 字段 |
| 恢复补偿 | `ReservationRecoveryService` / `ReservationRuntimeRecovery` | 任务行的租约、sale_version（abortRuntime 需要）；没有任务行时退化成不写墓碑的 release |
| SKU 顺序 | `ReservationQualificationOrder` | lua_turn_finished |
| 快审冲突 | `ReviewQuickCheck:88` | 任务行状态 |
| 审计 | `tb_reservation_risk_audit.job_id` | 任务行 id |
| 别名 | `tb_reservation_request_alias.job_id` | 任务行 id |
| 查询 | `queue.byRequest / byReservation / get`；`/attempts/{id}` | 任务行 |
| 就绪预热 | `AttemptWorkerReadiness:49` | 任务行 |
| 旧入口防护 | `TicketController:28` | 任务行 |

结论：这张表退出 HTTP 受理和全局调度，但仍然作为"每个尝试的处理记录 + 围栏 + 审计"保留。要彻底移除，需要把以上依赖都迁到 intent 表或订单表上，工作量明显更大，建议作为第三期。

---

## 14. 最小实施顺序与必要测试【建议】

| 步骤 | 内容 | 必要测试 |
|---|---|---|
| 1 | 协议泛化：`isAttempt` 改为 3/4，COUNT 过滤，ReviewQuickCheck / TicketController / Readiness / SoldOutSweep 修补，租约方法从登记逻辑中拆出 | 现有单元、MySQL 和集成测试全部不变地通过；新增 protocol 4 的行不会被 V2 领取、也不会计入 V2 active 的测试 |
| 2 | V3 Lua（把原型改成正式脚本） | 真实 Redis：并发容量、幂等、别名、MISMATCH、截止、active 上限、接管、旧 token、XACK+XDEL、用户在途 |
| 3 | T0 / 接管 / Tf | InnoDB：request_id 冲突的每个分支；active_owner 别名和清理；租约有效时不能接管；接管和旧 Worker 的 HELD 事务竞争（行锁串行）；围栏挡住重新受理后的成功 |
| 4 | 消费循环 + 续租 + FINISH，接入 `processAttempt` | 在每一步之后杀掉 Worker（T0 后、T1 后、prepare 后、Lua 后、T4 提交后 FINISH 前）→ 最终只有 1 个订单、库存一致 |
| 5 | V3 入口和查询状态映射 | 状态矩阵：MySQL 每种状态 × Redis 每种状态 → SUCCESS / FAILED / PROCESSING / UNKNOWN，不能出现误报 |
| 6 | 对账器、孤儿清理、waitingCap 发布 | 运行中 FLUSHALL / SIGKILL Redis → 无超卖、无重复订单、120 秒加 N 秒内没有残留的 REQUESTED / PROCESSING；对外宣布过的终态不会被改写 |
| 7 | 模式守卫、切换和回滚流程 | runtime_protocol=4 时 V2 节点拒绝启动，反之亦然；回滚时排空后才能切换 |
| 8 | 短场景（不是容量压测） | 和 V2 同一台机器，相同参数，对比 HELD TPS、每张票事务数、CPU |

**预期瓶颈【推断】**：

- 每张票的 MySQL 事务：T0、T1、T2、T3、T4 共 5 个，没有全局锁；T0 和 T1 合并后是 4 个。之前在你机器的 Docker 卷上实测 16 个连接约 310 次提交/秒，按 4 个事务算约 75 TPS。要到 170 TPS，需要更快的磁盘，或者进一步合并 T2 和 T3。
- JVM CPU 实测每张票 33～38ms（2 vCPU 环境），170 TPS 大约需要 6 个核。
- 受理 Lua 单 slot 实测 1.5～2.2 万次/秒，对 5000 QPS 有余量。

---

## 15. 需要你决定的问题

1. 同意任务表保留为"开始即插入"的处理记录，而不是在本期彻底移除吗？
2. 排队超时用 Tf 批量写围栏（这样能对外报告确定失败），还是不写围栏、只显示"未确认"？
3. V3 的受理和调度是否使用单独的 Redis 实例？库存 Redis 是否改成 `appendfsync always`？
4. POST 的 202 响应字段改为 `state=PROCESSING, durable=false`，前端（ticket-risk-web）是否需要同步修改？这一点我还没看前端代码。
5. 灰度方式：整体切换（推荐），还是按场次切换？
