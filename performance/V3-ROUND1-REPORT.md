# V3 第一轮：Redis Lua + Redis Stream 最小闭环（2026-10-31）

**标记说明**：
- **【已验证】**：有测试或实测数据。
- **【设计】**：实现选择，尚未专门实测。
- **【风险】**：剩余问题。

**本轮没有改动的部分**：

- 现有库存 Lua、风控和 Agent、支付、出票、Outbox 的业务逻辑都没改。
- 旧协议 3 的代码全部保留，可用于回滚。
- 没有删除任务表，也没有做事务合并或磁盘方面的优化。

## 1. 实际修改

### 新增（V3 专用，只在 `ticketing.reservations-v3.enabled=true` 时加载）

| 文件 | 作用 |
|---|---|
| `lua/v3_accept.lua` | 受理请求：requestId 幂等（指纹不同时返回 MISMATCH）；同一用户同一场次已有请求在处理时，新请求作为别名指向它（ALIAS）；限制单用户在途数和 waiting 容量（发布的容量值过期时退回保守值）；最后 XADD。BUSY、USER_LIMIT、CLOSED、MISMATCH 四种结果**不写入任何数据** |
| `lua/v3_start.lua` | **决定"开始"还是"排队超时"的唯一位置，原子执行**：截止时间用 Redis TIME 判断；检查全局 active 上限；写 Redis 租约；租约过期后由新消费者接管；req 记录丢失时按消息内容重建 |
| `lua/v3_settle.lua` | 只把 **MySQL 已经确定的结果**写回 Redis，同时释放名额，并对**这一条消息** XACK + XDEL。带 token 时，旧持有者无法结算 |
| `lua/v3_renew.lua`、`v3_ackdel.lua`、`v3_capacity.lua` | 续租；对已结算的消息补做确认和删除；发布容量并清理过期的 active 条目 |
| `V3Redis` | key 都在 `{tkv3}` 下，和库存 key 完全分开；封装脚本调用、消费者组、读自己的 PEL、按空闲时间接管（XCLAIM）、心跳时重置空闲时间 |
| `V3Entry` | HTTP 受理：**只调用一次 Redis，不写 MySQL**；202 响应为 `state=PROCESSING, durable=false, reliableAccepted=false, receiptSemantics=V3_PROCESSING`；MySQL 里还没有记录时，查询返回非权威的 PROCESSING |
| `V3JobStarter` | **开始处理时才插入协议 4 任务行**（状态 PROCESSING，带 lease_token）。已有行时：终态则返回终态，租约有效则判定为他人持有，租约过期则用 CAS 接管并更换 token。同用户同场次已有活跃尝试时写别名表（与协议 3 规则相同）。还负责写**排队超时的 MySQL 终态记录**（QUEUE_TIMEOUT 行） |
| `V3StreamWorker` | XREADGROUP（非阻塞读）、读自己的 PEL、按空闲时间接管、心跳（同时续 MySQL 租约、Redis 租约和 PEL 空闲时间）、容量发布、孤儿行重新处理；业务处理直接调用**原有的** `processAttempt` |

### 修改

| 文件 | 修改 |
|---|---|
| `ReservationAttemptStore` | `isAttempt` 改为同时认可协议 3 和 4，新增 `isV3`；协议 3 的 5 处计数 SQL 增加 `attempt_protocol<>4`；`initialize` 按协议拆分（V3 要求 runtime_protocol=4，且协议 3 已清空；V2 要求 3，且协议 4 没有 PROCESSING 行）；REQUESTED 状态的排空检查按协议区分；把同用户同场次释放 owner 的逻辑抽成公共方法给 V3 复用，行为不变 |
| `ReservationDispatch.initialize` | runtime_protocol 的校验增加 4 ← **审计漏掉的第 10 处**，实施时发现 |
| `ReservationV2Service` | `registerAttempt` 在 V3 下转给 V3Entry；`dispatchAttempts` 在 V3 下直接返回（旧的领取、过期、心跳逻辑都不运行）；新增 `processV3`、`pendingStatus`、`receiptSemantics` |
| `ReservationIngress.status` | MySQL 查不到时，再查 V3 的 Redis 状态。查到只返回非权威的 PROCESSING，查不到返回原有的 UNKNOWN，**绝不报失败** |
| `ReservationModeController` | V3 下 `receiptSemantics` 为 `V3_PROCESSING` |
| `ReviewQuickCheck`、`TicketController`、`AttemptWorkerReadiness` | 协议条件 `=3` 改为 `IN (3,4)` |
| `application-reservations-v2.yml` | 增加 V3 开关说明和切换、回滚步骤（只有注释，默认关闭） |
| 前端 `ticket-risk-web/src/App.tsx` | 两处提示文字：V3 的 202 显示"已受理，处理中；尚未确认抢票成功"；轮询时说明"查询不到结果不代表失败"。查询逻辑本身不用改：只有 `reservationSuccess && orderId` 才算成功，只有 `authoritative && terminal` 才算结束，现有逻辑已经满足。用 `tsc -b` 做了类型检查，通过 |

**协议隔离的最终清单（审计时列了 9 处，实施时多找到 1 处，共 10 处）**：

1. ～3. Store 里 5 条计数 SQL 增加协议过滤
4. ReviewQuickCheck
5. TicketController
6. AttemptWorkerReadiness
7. isAttempt
8. Store 中 REQUESTED 排空检查
9. Store 的 runtime_protocol 校验（加上反向回滚检查）
10. ReservationDispatch 的 runtime_protocol 校验

`ReservationSoldOutSweep` 有意保持 `=3`：它只在 V2 调度里运行，V3 不调用它；V3 的售罄由库存 Lua 自然返回。

## 2. 对你提出的 A～F 的落实情况

- **A 排队超时和开始处理互斥**【已验证】
  - Redis 层：`v3_start.lua` 对每个请求只给出 START 或 TIMEOUT 其中之一。
  - MySQL 层：任务表 `request_id` 唯一。PROCESSING 行（开始处理时插入）和 QUEUE_TIMEOUT 行（超时终态），谁先提交谁赢。终态行不会再变回 PROCESSING（全部 UPDATE 都带状态条件，或者先 `lockOwned`）。
  - 对外只在 MySQL 里有 QUEUE_TIMEOUT 行之后才报失败。
  - 失败窗口的处理：

    | 窗口 | 处理 |
    |---|---|
    | Redis 已判超时，终态记录还没写 | 不 ACK，查询显示 PROCESSING |
    | 终态记录已写，Redis 结算失败 | 消息重投后仍判 TIMEOUT，再写终态时发现已是终态行，结算成功 |
    | Redis 丢失数据后同一请求被重新受理 | 开始处理时遇到终态行，直接结束，不处理 |
    | 先开始后超时 | 写终态时发现已有处理中的行，交给它（DELEGATED），不报失败 |

  - 两个顺序和 20 轮并发竞争都有测试。
- **B 库存 Lua 在 MySQL 事务中调用**【已验证，复用现有逻辑，没有新增补偿代码】
  - Lua 只在 `reserveInOrder` 事务内、持有任务行锁并核对 token 时执行。
  - 事务回滚或 HELD 失败：abort 时 intent 为 REQUESTED，执行 `abortRuntime`（写墓碑、归还库存），intent 改为 FAILED。
  - 提交结果未知：abort 回读到 intent 为 COMPLETED，不补偿。
  - 连接中断或数据库不可用：行保持 PROCESSING，不结算；租约过期后被接管，Lua 幂等，返回同一份预占；或者 120 秒后由现有恢复任务 `abortRuntime`。
  - 消费者崩溃或租约被接管：新 token 让旧持有者的所有 MySQL 写入在 `lockOwned` 处失败；接管必须等行锁，不会和旧持有者的 Lua 交错执行。
- **C 查询不把"查不到"当失败**【已验证】
  - 成功的唯一依据：MySQL 任务行为 RESERVED 且订单存在（`view` 会校验订单）。
  - MySQL 没有记录时返回 PROCESSING 或 UNKNOWN（authoritative=false，terminal=false）。
- **D XACK 和 XDEL**【已验证】
  - 只在 MySQL 已经给出结论（终态、终态记录、别名、交给他人、冲突）后，在 settle 脚本里对**同一个 id** 执行 XACK + XDEL。
  - FULL、被他人持有、异常、行仍为 PROCESSING 的情况都不 ACK。
  - Redis 有预占而 MySQL 没有订单时，由现有恢复任务处理（intent 为 REQUESTED 且超过 120 秒 → `abortRuntime`）。V3 的行都有任务行，所以总是走带墓碑的补偿。另外孤儿清理会处理"行一直是 PROCESSING 但消息已经没了"的情况。
- **E 协议隔离**【已验证】：见上面 10 处。另有一个测试确认 V2 的领取在 V3 下拿不到协议 4 的行，并且 V2 调度器在 V3 下不做任何事。
- **F 受理容量**【设计，单元测试已验证公式】

  ```
  cap = max(min(activeLimit, hard − active),
            min(按 Worker 和处理耗时的预测,
                饱和时实测消化速率 × 4s + activeLimit,
                hard − active))
  ```

  - 按 Worker 和处理耗时的预测，沿用协议 3 的预算公式：空闲槽位 + 4.75s / 平均处理耗时 × activeLimit。
  - "实测消化速率"**只在 waiting 不为空时采样**，避免把需求不足误判成处理能力。
  - 名额不会永久泄漏：waiting 名额到截止时间自动失效；active 中租约过期超过 2 倍的条目会被清理；发布的容量超过 3 秒没有更新，就退回保守值 activeLimit。

## 3. 测试结果

| 测试 | 结果 |
|---|---|
| 全部单元测试（含新增的 V3CapacityTest） | **371 / 371** |
| `V3RedisScriptTest`（真实 Redis 7.0.15） | **10 / 10**：关闭时不受理、幂等和消息字段、同用户别名及释放、BUSY 或 USER_LIMIT 时不写数据、租约接管且旧 token 无法结算、超时在写终态记录前不 ACK、active 满时消息留在 PEL、req 丢失后重建、空闲接管和心跳保活、容量发布 |
| `V3StreamIT`（真实 MySQL 和 Redis，完整 Spring 上下文，原有风控、库存 Lua 和 HELD 代码；见下表） | **11 / 11** |
| 回归：V2 集成测试（开批量登记和闸门）12 个；Redis 到达顺序模式 5 个 | **17 / 17** |
| 回归：MySQL 测试 33 个；闸门 Redis 测试 11 个 | 全部通过 |

`V3StreamIT` 的 11 个用例：

| 用例 | 验证内容 |
|---|---|
| 正常流程 | 202 为 PROCESSING，HTTP 路径上 MySQL 没有行；处理后 RESERVED、订单和 intent 一致；MySQL 库存和 Redis 库存都是 99；Stream 和 PEL 清空；同一 requestId 重试拿到 MySQL 里的成功结果，不会产生第二单 |
| HTTP | 真实 HTTP 返回 202 和 PROCESSING；模式接口返回 `V3_PROCESSING`；未知 requestId 返回非权威、非终态 |
| 同一用户换 requestId 重复点击 | 作为别名，只有一单；按别名查询能看到目标请求的结果 |
| 排队超时 | 写终态记录之前显示 PROCESSING 且不 ACK；写之后是权威终态；**把 V3 的 Redis 数据全部清空后，用同一 requestId 重新受理也不会被处理**；库存仍为 100 |
| 开始处理和超时互斥 | 两种先后顺序都只有一方生效 |
| 二者并发竞争 20 轮 | 每轮都只有一方胜出，只有 1 行 |
| Lua 执行后进程"死亡"（抛 Error） | 租约过期后被接管，只成交一次；旧持有者醒来后 reserve 或 abort 都不生效 |
| HELD 失败 | 墓碑写入、库存归还，MySQL 和 Redis 都是 100；状态为权威终态 |
| 提交成功但调用方收到异常 | **不补偿**，状态仍为 RESERVED，库存 99 |
| V3 的 Redis 数据在处理中途全部丢失 | 3 个请求重新受理，每个各只有 1 单，库存 97；原来持有租约的那个在租约有效期内没有被别人处理，由孤儿清理补完 |
| 协议隔离 | 协议 3 的领取和调度看不到协议 4 的行 |

## 4. V2 和 V3 对比

**条件**：同一台 2 vCPU 云主机，应用、压测客户端、MySQL 和 Redis 全在这台机器上，同一个压测工具，同样的统计口径；每组预热 5 秒，测量 30 秒，开放模型，30% 请求走 REVIEW。

- V2 配置：Redis 到达顺序 + 闸门 + 批量登记，workers 和 active_limit 都是 16。
- V3 配置：Redis 到达顺序 + V3，workers 和 active_limit 都是 16。

数据：`results/v3-round1-20261031/`。

| | V2 50/s | **V3 50/s** | V2 100/s | **V3 100/s** | V3 50/s（active 8） |
|---|---:|---:|---:|---:|---:|
| HELD TPS（稳态） | 22.0 | **20.4** | 14.9 | **12.6** | 19.7 |
| 总成交 | 1067 | 771 | 1095 | 469 | 712 |
| 202 / 429 | 920 / 580 | 698 / 802 | 1040 / 1960 | 474 / 2526 | 657 / 843 |
| 202 之后排队超时 | 0 | 57 | 46 | 100 | 14 |
| POST p50 / p95 | 2585 / 3255 ms | **52 / 318 ms** | 236 / 3252 ms | **77 / 395 ms** | 51 / 197 ms |
| InnoDB 行锁等待总时长 | 461 s | **13.6 s** | 537 s | **13.9 s** | — |
| 单个任务的 Worker 处理耗时 | 213 ms | 653 ms | 264 ms | 1017 ms | 355 ms |
| 每单 JVM CPU | 51 ms | 69 ms | 65 ms | 118 ms | 74 ms |
| 不变量（MySQL 库存 = Redis 库存 = 订单数） | 一致 | 一致 | 一致 | 一致 | 一致 |

**结论（数据是实测，原因分析是推断）**：

1. 全局协调锁的争用基本消失：行锁等待从 461～537 秒降到约 14 秒；HTTP 延迟降到原来的 1/50（V2 的 202 要等 MySQL 提交，V3 的 202 只表示 Redis 已受理，两者语义不同）。
2. **在这台机器上，HELD TPS 没有提升**（V3 比 V2 低 7%～15%）。active 降到 8 后，每个任务的处理耗时减半，但吞吐不变，仍约 20/s。这说明瓶颈已经不在协调锁上，而在每单本身的处理开销上，也就是 2 核 CPU 加 MySQL（与 batch-claim A/B 的结论一致）。并发更高只会让每单更慢，并造成更多 202 之后的超时。
3. V3 每单的 CPU 比 V2 高约 35%。JFR 采样里排第一的是 MySQL 驱动为每条语句创建 `CancelTask` 时复制连接属性（这是已知开销）。具体原因还没定位，可能是 V2 的批量登记被每单单独插入任务行和写超时终态记录所替代。按你的要求，**这一轮没有做优化**。

## 5. 剩余风险

1. **容量**：active=16 时仍有 3%～7% 的 202 请求最终排队超时（有终态记录，对外显示失败，状态正确，但体验不好）。消化速率反馈只在饱和时生效，收敛偏慢。建议下一步先调低 active_limit，或改进速率估计。
2. **对外失败的时间点**：超时失败要等消费者读到消息并写入终态记录后才对外显示；所有消费者都停了的时候，请求会一直显示 PROCESSING（这是符合契约的选择）。
3. **Redis 数据丢失**（everysec 下最多约 1 秒，或者数据集整体丢失）：
   - 已受理、还没开始的请求会丢，客户端看到 UNKNOWN 后用同一 requestId 重试即可；
   - 已开始的请求由孤儿清理（租约过期超过 60 秒）或 120 秒恢复任务处理；
   - Redis 丢失后 `mode` 为空时，会**自动重新打开受理**（auto-open=true）。如果需要人工确认后再开，把 auto-open 设为 false。
4. **租约**：MySQL 租约是唯一真正的保护。Redis 租约只用于调度，两者不一致时会多等一个租约周期（15 秒）。如果一个 Worker 卡住但心跳线程还活着，它会一直续租（V2 也有同样问题）。
5. **消息格式**：带 `v=1` 版本号；解析失败的消息会被丢弃并记录错误。灰度时必须保证所有节点版本一致（这次采用整体切换，没有问题）。
6. 受理脚本里通过拼接 key 读取 owner 的状态（同一 hash tag，同一 slot）。单实例 Redis 没有问题，将来上 Redis Cluster 需要复核。
7. 前端只做了类型检查，没有在浏览器里实际跑过 V3 流程。

## 6. 切换与回滚（整体切换）

**切换**：
1. 协议 3 设置 `accepting=false`。
2. 等协议 3 的 WAITING 和 PROCESSING 清零，REQUESTED 状态的 intent 处理完。
3. 执行 `UPDATE tb_reservation_dispatch SET runtime_protocol=4,ordered_lua_enabled=1,redis_lane_enabled=1 WHERE id=1`。
4. 所有节点用 V3 配置重启（`attempts.enabled=true`、`ordered-lua=true`、`redis-ordered-lane=true`、`reservations-v3.enabled=true`，同时关闭 batch-register、redis-gate、batch-claim；配置不对时启动会被拒绝）。

**回滚**：
1. 执行 `HSET {tkv3}:cfg mode CLOSED`。
2. 等 `XLEN {tkv3}:stream` 为 0，且协议 4 没有 PROCESSING 行（V2 启动时会检查这一点）。
3. 把 runtime_protocol 改回 3，用 V2 配置重启。

协议 4 已有的订单仍可以正常支付和出票。
