# attempt 链路吞吐改造（第一批：入口与登记）

日期：2026-10-31。范围按约定的"推荐方案"：Redis 准入闸门（只做提前拒绝）、批量登记、批量领取、并发参数可配置，外加服务端指标采集工具。
**Redis 有序预占、维护计数（maintained counters）本轮没有做。** 造数据工具也没有做，原因见文末。

所有新能力都在独立开关后面，**默认全部关闭**。开关全关时，代码路径与改造前一致；已有的 349 个单元测试、MySQL 测试和各个 IT 都已重新跑过（见"验证"）。

## 1. 改了什么

| 开关（前缀 `ticketing.reservations-v2.attempts.`） | 默认 | 作用 |
|---|---|---|
| `workers` | 4 | 本节点 attempt worker 槽位数（原来写死为 4）。全局上限仍是 `tb_reservation_dispatch.active_limit` |
| `redis-gate` | false | 在**任何 MySQL 访问之前**用一段 Redis Lua 判断是否明显过载，过载直接 429 |
| `batch-register` | false | 多个 HTTP 登记合并成一个事务：只锁一次 id=1 协调行，只提交一次 |
| `batch-claim` | false | 一个领取事务一次授予多笔 attempt |
| `db-busy.max-waiting` | 不设置 | 不设置时保持原规则（有线程在等连接或连接池满就拒绝）；设为 N 时，只有等待线程数超过 N 才拒绝 |

### 1.1 Redis 准入闸门（`AttemptAdmissionGate`）

- 判定顺序：同一 requestId 之前被闸门放行过（有标记 key）→ 一律放行，保证幂等重试能拿到原回执；闸门状态不存在（Redis 重启、所有节点停止发布）→ 放行；等待计数达到上限 → 429；否则计数加一、写标记、放行。
- **它不是权威**：MySQL 的 `register` 在锁内照常复核容量、等待预算、用户在途数和幂等。计数有偏差，最多导致多拒或少拒几个请求，不会突破 512 等上限。
- 计数维护：放行时加一；attempt 离开 WAITING 时（首次领取或超时，提交后回调）尽力减一；每 200ms 用 MySQL 的真实数量覆盖一次。状态 key 带 TTL，所有节点都停止发布时会自动过期，闸门退化为全部放行。
- Redis 出任何异常都放行，并计入 `attempt.gate.decision{outcome=redis_error}`。
- 拒绝计入 `attempt.admission.rejected.internal{reason=GATE_REJECT,detail=redis_gate}`。对外仍是原来的 `429 SYSTEM_BUSY`，不暴露内部原因。
- 所有 key 使用同一个哈希标签 `{attempt-gate}`，与库存 key 不在同一槽位，库存脚本不受影响。
- 已知边界：如果 Redis 数据丢失（标记没了），在丢失前已经登记成功的请求，用原 requestId 重试时，系统繁忙期间可能拿到 429 而不是原回执。可以用按 requestId 查询的 GET 接口确认状态。

### 1.2 批量登记（`AttemptRegistrar` + `ReservationAttemptStore.registerBatch`）

- 登记线程按"凑满 16 条或等满 5ms"组成一批，一个事务、一次 id=1 加锁、一次提交。
- **每条请求的规则和单条 register 完全相同**：原来的 register 方法体被抽成 `registerLocked`，单条和批量都调它。同一批里，后面的请求能看到前面请求插入的行，所以容量、用户在途数、同一用户第二次点击走 alias、同一 requestId 重复提交，结果都和顺序调用一致。
- 单条请求被拒（`BusinessException`、容量 429、唯一键冲突）时，只回滚到这一条的保存点，同批其他请求不受影响。其他数据库异常（死锁、锁等待超时、提交失败）视为整批失败，原异常原样返回给这一批的每个调用方。入口层对它们的处理和单条失败完全一样，提交结果不明时仍是 503 `VALIDATION_UNAVAILABLE`。
- HTTP 线程最多等 `batch-register-wait-ms`（默认 1000ms），超时返回 503 `VALIDATION_UNAVAILABLE`。**这不是回滚**：这一批之后仍可能提交成功，届时可按 requestId 查到，这正是这个 503 原有的语义。
- 本地缓冲（默认 1024）满了，直接返回 429，不做任何数据库操作。
- 已知小偏差：同一批里某条请求先让旧 WAITING 过期、随后又被拒并回滚到保存点时，`attempt.queue.timeout` 计数可能多记一次。只影响指标，不影响数据。

### 1.3 批量领取与 worker 数

- `claimBatch(n)` 在一个事务里循环执行与 `claim` 相同的逻辑：优先恢复过期租约；每授予一笔后重新按全局 `active_limit` 计数；候选行加锁后重新取数据库时间；过了截止时间就置为 QUEUE_TIMEOUT，不会迟到授予。
- 调度线程先占好本地槽位，再一次性领取。线程池拒绝某笔授予时，立即对它执行 `defer`（释放租约，保持 PROCESSING），其他 worker 或节点马上就能恢复它，不用等 15 秒租约过期。
- **注意：`active_limit` 由 `ticketing.reservations-v2.workers` 决定**。`ReservationDispatch.initialize` 要求两者一致，否则启动失败。所以把全局并发从 4 调到 16，需要三处同时改：
  1. `UPDATE tb_reservation_dispatch SET active_limit=16 WHERE id=1`（先排空在途任务）；
  2. 所有节点配置 `ticketing.reservations-v2.workers=16`；
  3. `ticketing.reservations-v2.attempts.workers` 和 `ticketing.pools.sale.max` 也设为 16 及以上。

## 2. 验证

环境：云端 Linux 容器，MySQL 8.0.46（本地磁盘，`innodb_flush_log_at_trx_commit=1`、`sync_binlog=1`），Redis 7.0.15，JDK 21，依赖使用你本机 `benchmark-classpath.txt` 中的同一套 jar。

| 范围 | 结果 |
|---|---|
| 全部单元测试（68 个测试类，不含 MySQL 测试和 IT） | 349 通过，0 失败；其中新增 29 个 |
| 已有 MySQL 测试：ReservationAttempt / QualificationOrder / AttemptQuick / VectorMigration | 27 通过 |
| 新增 `AttemptThroughputMysqlTest`（真实 InnoDB 保存点、SKIP LOCKED、2 个登记节点和 2 个领取节点并发） | 3 通过；并发期间 0 个数据库错误，没有重复授予，`active_limit` 从未被突破 |
| 新增 `AttemptAdmissionGateRedisTest`（真实 Redis 跑 Lua） | 6 通过 |
| IT（开关全开）：DurableAttemptLifecycle / OrderedQualification / QuickReviewAttempt / VectorReservation 各自的 13 个测试方法 | 13 通过 |
| `AttemptAcceptancePerformanceIT`（开关全关和全开） | 均通过，所有不变量检查都成立 |

说明：
- `AttemptAdmissionGateRedisTest` 需要加 `-Dattempt.gate.redis.port=<端口>` 才会运行，默认跳过。它只使用 DB 15 中自己创建的 `{attempt-gate}` key。`AttemptThroughputMysqlTest` 和现有 MySQL 测试一样，需要 `-Dticketing.attempts.mysql=true`，并使用归属明确的临时 schema。
- 以 IT 的方式整类运行 DurableAttemptLifecycleIT 时，它从 ReservationV2IT 继承来的 10 个方法在这个环境下会失败。改造前的代码同样是这 10 个失败、失败列表完全一致，与本次改动无关。你那边的 harness 只运行各个 IT 自己声明的方法。

## 3. 性能观察（`AttemptAcceptancePerformanceIT`：50 个请求、并发 10、单 SKU、A 协议）

这是正确性对照用的短批次，不是容量测试。数字只用于判断方向。

| 配置 | HTTP QPS | 202 QPS | HELD TPS | HELD 数 | 拒绝构成 |
|---|---:|---:|---:|---:|---|
| 原样，active_limit 4（3 轮） | 54.6～71.9 | 23.0～26.2 | 9.3～10.5 | 16～24 | DB_BUSY 17～20，waiting_budget 9～14 |
| 开关全开，active_limit 4（3 轮） | 63.0～79.7 | 23.7～36.5 | 10.5～13.2 | 16～29 | GATE_REJECT 20～24，waiting_budget 1～10，**DB_BUSY 0** |
| active_limit/workers 16，单条登记（2 轮，仅实验） | 16.7～21.9 | 同左（全部 202） | 9.1～9.9 | 50 | 无 |
| 16 + 闸门 + 批量登记（2 轮，仅实验） | 22.6～26.5 | 同左 | 8.4～8.5 | 50 | 无 |
| 16 + 全部开关含批量领取（2 轮，仅实验） | 30.2～37.0 | 同左 | 7.5～7.8 | 50 | 无 |

结论：

1. **DB_BUSY 消失了**。过载请求在 Redis 闸门被提前拒绝，不再占用 MySQL 连接。
2. **单 SKU 下 HELD TPS 被卡在约 10/s，加并发没用**。`attempt.ordered.lua.transaction` 的 P95 是 100～160ms，同一 SKU 严格按顺序执行，上限就是每秒大约 1000÷100 次。把 worker 加到 16 以后，大量 worker 拿到还没轮到自己的 attempt 只能 defer 再重领：同一批次的 defer 次数从 73 次涨到 159～245 次，TPS 反而略降。批量领取把这种空转放大得最明显。
3. 所以**下一步必须做 Redis 有序预占**，把 MySQL 移出同 SKU 的串行段。在那之前，单个热点 SKU 场景不建议打开 `batch-claim`，也不建议把 worker 调大。多 SKU 场景下，这些开关的收益需要用 k6 稳态压测来确认，这一轮没有测。
4. 作为对比：同样的代码，Windows/Docker 环境上次测得约 2.4 TPS，这次 Linux 本地磁盘约 10 TPS。环境差异大约 4 倍。

## 4. 服务端指标采集：`performance/attempt_metrics_collector.py`

压测期间按固定间隔抓取 `/actuator/prometheus`，保存每一次的原始样本，并输出时间窗口内的汇总：
- 各计数器的增量和每秒速率：登记数、429 构成、闸门决策、首次/恢复领取、超时、批量等待超时等；
- 按 operation/outcome/phase 分组的事务耗时分位数（用窗口内的 SLO 桶增量计算，是近似值）；
- 连接池、sale 线程池、登记缓冲的峰值。

只读，只允许 localhost，只用 Python 标准库。用法：

```
python performance\attempt_metrics_collector.py --url http://127.0.0.1:8082/actuator/prometheus --seconds 330 --interval 1
```

## 5. 第二批：Redis 到达顺序的 SKU 通道（`attempts.redis-ordered-lane`）

### 5.1 做了什么

这是 ordered-lua 协议的一种模式，用 `ReservationQualificationOrder` 内部的一个开关实现：
- **资格就绪顺序 = 预占 Lua 到达 Redis 的顺序**（你已确认的定义）。Redis 单线程执行 Lua，谁先到谁先扣。
- 取消 MySQL 里的 SKU 游标：prepare 不再写 `tb_reservation_sku_order`、不再分配 `qualification_seq`，所以 prepare 也不再锁每个 SKU 的那一行；`requireTurn` 不会再抛 `Pending`，worker 不会再因为没轮到自己而 defer、重领。
- **保留不变的部分**：
  - 意图仍在 Lua 之前提交（prepare 事务）；
  - Lua 成功后仍在同一个短事务里提交 `lua_turn_finished=1`，确认事务仍然只从 Redis 的 hold 标记读结果，不会重复执行 Lua；
  - abort 仍然先写 Redis 墓碑，迟到的 Lua 返回 -5，无法"复活"；
  - Armory 门禁、风险 fence、job/intent 行锁、10 分钟支付期限、售罄扫描都没变。
- Lua 本身是幂等的（同一个 reservation key 再调用会返回已有的 hold），所以"Lua 成功但 `lua_turn_finished` 还没提交时进程崩溃"这种情况，恢复后重试不会重复扣库存。这一点有专门的 IT 覆盖。

### 5.2 激活与安全

- 新迁移 `V114__attempt_redis_arrival_lane.sql` 只新增一列：`tb_reservation_dispatch.redis_lane_enabled`，默认 0。`schema.sql` 同步加了这一列。
- 启动检查（`ReservationAttemptStore.initialize`）：
  - 开关要求先开启 ordered-lua；
  - 开关打开但没有 V114 的列：拒绝启动；
  - 节点配置和库里持久化的模式不一致：拒绝启动；
  - 打开前，如果还有"已在 SQL 游标上发布、但还没完成"的 PROCESSING 任务：拒绝启动；
  - 关回 SQL 游标模式前，如果还有在 Redis 模式下处理中的任务：也拒绝启动（原有检查）。
- 切换步骤：停掉所有节点 → 等在途任务排空 → 执行 `UPDATE tb_reservation_dispatch SET redis_lane_enabled=1 WHERE id=1` → 所有节点带上 `ticketing.reservations-v2.attempts.redis-ordered-lane=true` 启动。
- **你的开发库是用 FreshStart 按旧版 `schema.sql` 建的，没有这一列。需要手动执行一次 V114**。开关关闭时，没有这一列也能正常启动。

### 5.3 验证

| 范围 | 结果 |
|---|---|
| 新增 `RedisArrivalLaneTest`（H2，覆盖意图先提交、不产生游标、后发布的请求可以先预占、幂等重试、已完成的轮次不重复、abort 墓碑、启动时的模式和排空检查） | 11 通过 |
| 新增 `RedisArrivalLaneIT`（真实 MySQL + Redis）：前一个请求的确认被暂停时后一个仍能完成；后资格的请求可以先到 Redis；Lua 结果不明 → abort 写墓碑 → 迟到命令返回 -5；轮次提交后被回收的任务只复用 hold、保持 10 分钟期限；Lua 之后、轮次提交之前崩溃，重试不会重复扣减 | 5 通过 |
| Redis 模式下的 QuickReview（3 个）和 Vector（4 个）IT 方法 | 7 通过 |
| 原 SQL 游标模式的 OrderedQualificationIT（3 个） | 仍然通过 |
| OrderedQualificationIT 在 Redis 模式下运行 | 3 个按设计失败：它们断言的是 SQL 游标语义（`Pending`、`next_lua_seq`），已经由上面的 RedisArrivalLaneIT 替换 |
| 全部单元测试 / 现有 MySQL 测试 | 360 通过 / 30 通过 |

### 5.4 性能（同一个 50 请求 IT，单 SKU，active_limit/workers 16）

| 配置 | HELD TPS | 端到端 P95 | 同批 defer/重领次数 |
|---|---:|---:|---:|
| SQL 游标（第一批数据） | 9.1～9.9 | 2.2～3.3 s | 73 |
| Redis 到达顺序 | **17.0～17.1** | **1.1 s** | **0** |
| Redis 到达顺序 + 闸门 + 批量登记 | 16.2～19.9 | 1.0 s | 0 |
| 再加批量领取 | 18.4～19.7 | 1.2～1.3 s | 0 |
| Redis 到达顺序 + 向量库存（B 协议）+ 闸门 + 批量登记 | 17.3 | 1.1 s | 0 |

- 单 SKU 的串行瓶颈已经去掉：TPS 翻倍，端到端 P95 减半，空转降到 0。
- **在这次测试里，新的上限已经不是通道了**：50 个请求在大约 2.2 秒内全部发完，持有窗口约 2.5 秒，基本就是"来多少成交多少"。另外这台云端容器只有 2 个 CPU，MySQL 也跑在上面，JVM 已经用到约 1.45 核。要知道真实上限，必须用 k6 做更大请求量的持续压测，而且压测机和数据库要分开。
- 有了 Redis 到达顺序以后，批量领取不再有空转问题，但在这个规模下收益也不明显。

## 6. 没做的部分

- ~~Redis 有序预占~~：已在第 5 节完成。
- **维护计数**：按约定先不做。有死锁风险，批量登记以后 COUNT 的成本已经被一整批分摊。
- **造数据工具**：需要按你的实际开售流程（Armory 装配、token、风险画像、库存量）在你的环境里核对后才能写，否则很可能写出跑不通的脚本。下一轮可以先在这个云端环境里把流程跑通，再交付给你。

## 7. 回退

- 所有开关保持默认关闭即可。V114 只新增一列且默认 0，留着不影响旧模式，不需要回退数据库。
- 第一批改动前的原文件和 diff：`performance/results/attempt-throughput-change-20261031/`；第二批：`performance/results/attempt-redis-lane-change-20261031/`。
