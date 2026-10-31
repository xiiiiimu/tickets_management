# 第一阶段：503 语义修复 + 协调锁计时（2026-10-31）

本阶段没有修改 batch-claim 配置，没有实现 lock-local-counters，也没有改动库存 Lua、风控、支付或出票逻辑。

## 1. 修改内容

| 文件 | 修改 |
|---|---|
| `AttemptRegistrar.java` | 每个排队请求有一个原子状态：从 QUEUED 变为 TAKEN，或从 QUEUED 变为 CANCELLED，两者只有一个能成功。超时时如果还能撤销 → 返回确定的 429（`registrar_wait_cancelled`），并从缓冲区移除，这个请求**永远不会进入 MySQL**。如果已被取走 → 再等最多 `batch-register-taken-wait-ms`（默认 4000ms）拿确定结果；超过这个上限仍返回 503 结果未知，**不依赖** 3 秒事务超时一定会生效。停止时：正在执行的批次先跑完（最多等一个 taken-wait），从未被取走的请求返回确定的 503 SYSTEM_BUSY。新增 `Settlement` 回调：只上报确定的结果（已提交且新增 WAITING / 已提交但没有新增行 / 确定未写入）；调用方已经放弃等待后才提交的，也会上报；结果未知时**不上报**。 |
| `AttemptAdmissionGate.java` | 闸门占用改为：已提交的 waiting + `held`（已放行、MySQL 还看不到）+ `fresh`（刚提交、最近一次对账还没算进去）。**对账只覆盖 waiting，从不改动 held。** 每次放行有唯一 token（held 的成员是 `requestId|token`）。释放时只删除自己那一条，并且只在 marker 的值仍等于自己的 token 时才删除 marker，重复调用不会产生副作用。结果未知的放行在 `redis-gate-hold-ms`（默认 10s，启动时检查它大于调用方最长等待时间加 1s）到期后自动释放。fresh 只删除"对账读取 Redis 时间之前"就已登记的条目（Redis 时间在查 MySQL 之前读取），保证删掉的行一定已经算进 MySQL 计数。 |
| `ReservationV2Service.java` | 交给 registrar 之前确定的结果（已有回执、暂停接纳、未就绪、DB_BUSY、单条 register 在事务内被拒）由入口自己上报；交给 registrar 之后由 registrar 上报；DataAccess 或事务异常（结果未知）不上报。 |
| `AttemptCoordinatorLock.java`（新增） | 统一获取协调行锁并计时，替换 `ReservationAttemptStore` 里 protocol 3 的 4 处加锁：register、register_batch、claim、claim_batch。 |
| `ReservationAttemptStore.java` | 改为调用上面的 4 处加锁；新增 `registrarCancelled()`。SQL、顺序和事务边界都没变。 |
| `application-reservations-v2.yml` | 补充 taken-wait 和 hold-ms 的说明（都是注释，默认值不变）。 |

### 计时口径：`attempt.coordinator.lock{operation, phase, outcome}`

- `phase=wait`：从发出 `SELECT … FOR UPDATE` 前到它返回 = 排队等锁的时间，加一次语句往返（没有争用时不到 1ms）。outcome 为 `acquired`，或 `not_acquired`（超时、死锁、断连）。
- `phase=hold`：从这条语句返回（拿到锁）到事务完成回调（COMMIT 或 ROLLBACK 已返回）= 锁内所有语句加上提交（含 redo 刷盘）。它是服务端实际持锁时间的上界，最多多出一次提交往返。outcome 为 `commit` / `rollback`。
- 原来的 `attempt.transaction.duration phase=body` 包含等锁时间，**不能当成持锁时间**。
- 没有覆盖的地方：`ReservationDispatch` 里 protocol 2 的 3 处旧锁（protocol 3 不走这些路径）。

## 2. 测试结果

| 测试 | 结果 |
|---|---|
| 全部单元测试（不含 MySQL 和 IT） | 367 通过。另有 11 个闸门 Redis 测试在没有 Redis 时被跳过（MiniRunner 把跳过算成 failed，改动前也一样） |
| `AttemptAdmissionGateRedisTest`（真实 Redis 7） | 11 / 11 通过 |
| `AttemptRegistrarTest` | 14 / 14 通过；240 个请求的撤销与取走竞态测试重复跑了 5 次都通过 |
| `AttemptThroughputEntryTest` | 13 / 13 通过 |
| `AttemptThroughputMysqlTest`（InnoDB） | 6 / 6 通过（跑了 2 次） |
| 其余 MySQL 测试（ReservationAttempt、AttemptQuick、QualificationOrder、VectorMigration） | 5 + 7 + 8 + 7 通过 |
| IT（开 batch-register + 闸门，不开 batch-claim，SQL 游标排序） | Durable 2、Ordered 3、Quick 3、Vector 4，共 12 / 12 通过 |
| IT（Redis 到达顺序模式） | RedisArrivalLaneIT 自己的 5 个方法逐个运行都通过；Quick 3、Vector 4 通过 |

已知且和本次改动无关：在 Redis 到达顺序模式下跑 OrderedQualificationIT 的 3 个方法，它们断言的是 SQL 游标语义，用改动前的代码跑同样失败。整类运行 RedisArrivalLaneIT 时，继承来的方法会失败，这和之前 DurableAttemptLifecycleIT 整类运行的情况相同。

新增的关键测试：

- **撤销与取走竞态**：被撤销的请求绝不会执行，被执行的请求绝不会返回 429，每个请求只上报一次结果。
- **InnoDB**：协调行被另一个连接持有 900ms 时，排队的请求被撤销（MySQL 里查不到这一行），已取走的请求等到提交后拿到回执；同一次测量 wait 约 890ms、hold 约 7～21ms。
- **超过 3 秒事务超时**：持锁 4.5s 时，结果是整批数据库异常（入口会映射为 503 未知），没有写入，计时记为 `not_acquired`，没有 hold 记录。
- **两个节点**：同一个 requestId，加上同一用户用新 requestId 重复点击，跑了 15 轮。每轮只有 1 个尝试，所有成功的回执都指向同一个 attempt。
- **闸门**：对账不会清掉还没提交的放行；释放只删除自己的那一条，迟到的释放不会误删同一 requestId 后来的那次放行，也不会影响其他请求；结果未知的放行只在到期后释放；"已提交但对账还没看到"的行仍被计数。

## 3. 一次短场景（不是容量压测）

配置和上一轮的 new-r50 相同（Redis 到达顺序 + 闸门 + 批量登记，workers 和 active_limit 都是 16，wait-ms 2500，**没有开 batch-claim**）。发送速率 50/s，预热 3s，测量 10s。机器仍是 2 vCPU，所有组件在同一台上。

- HTTP：202 有 276 个，429 有 224 个，**503 为 0**（上一轮 30 秒的运行里 1662 个全是 503）。
- 所有对象的计数一致：闸门放行 469 = 提交 347 + 撤销 122；RESERVED 347 = 订单数 = 库存扣减数；没有出现 `taken.timeout`。
- HELD TPS 16.1。上一轮是 25.0，但那一轮把"先回 503、后台再提交"的请求也算进去了，现在这部分改为确定的 429；而且闸门现在把未提交的放行也计入占用，拒绝得更早。

| 操作 | 次数 | 等锁 mean / max | **持锁** mean / max | 原 body mean |
|---|---:|---:|---:|---:|
| claim | 353 | 411 / 924 ms | **39 / 172 ms**（其中提交约 18ms） | 435 ms |
| register_batch（每批约 14 条） | 24 | 313 / 868 ms | **290 / 671 ms** | 598 ms |

- **锁已经饱和**：总持锁时间约为 353 × 39ms + 24 × 290ms ≈ 20.7s，整个运行约 18～20s 墙钟时间，这把锁几乎一直有人持有。claim 的 body 里约 95% 是在等锁。
- 交叉验证：本次运行期间 InnoDB 的 `Innodb_row_lock_waits` 增加 405 次，`Innodb_row_lock_time` 增加 153s，平均约 378ms，和应用侧测到的等锁时间一致。
- 结论：每张票要 1 次 claim（持锁约 39ms，其中一半是提交）。按这个持锁时间，光是 claim 就把这把锁限制在每秒约 25 个以内。第二阶段的 batch-claim（一次持锁发多张票）正好针对这一点。register_batch 每条约 20ms 的锁内耗时，是第三阶段要处理的对象。

## 4. 剩余风险

1. **延迟**：已被取走的请求，HTTP 最长等待时间为 wait 加 taken-wait（本次配置是 2.5s + 4s）。本次 POST p50 是 2.6s，主要因为配置的 wait=2500；第二阶段可以考虑调小。
2. **taken-wait 只限制调用方等多久，不能终止数据库操作**：如果驱动一直挂住，registrar 线程会卡住，后面排队的请求都会在等待时间到后被撤销，返回 429（不会出错，但期间没有吞吐）。可以用 `attempt.register.batch.inflight.ms` 告警。
3. **闸门是建议性的**：结果未知的请求在 hold 到期前会被重复计数（偏保守）；leftWaiting、Redis 时钟回拨或对账竞态最多让计数偏差一个对账周期。闸门偏松时，MySQL 仍会按权威规则拦住。
4. **2 个节点用同一个 requestId** 时，后到的那条可能拿到 DuplicateKey（入口会映射为 503 结果未知），而不是原回执，这和改动前一样。用同一个 requestId 再查一次就能拿到回执。
5. **阶段性收益和容量**：撤销加上更严格的闸门，会把原来"迟到的成功"变成确定的 429，所以在同一台机器上，HELD TPS 低于上一轮的 25。这是语义正确的代价，真正的提升要靠第二、三阶段降低锁争用。

## 5. 文件

- `results/phase1-20261031/phase1.diff`：相对于上一轮代码的完整 diff。
- `results/phase1-20261031/p1-short-r50-10s.json`：短场景原始数据（包含 `attempt.coordinator.lock` 的全部计时）。
- `results/phase1-20261031/innodb-row-lock-before-after.txt`：短场景前后的 InnoDB 行锁计数。
