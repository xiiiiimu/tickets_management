# V3 性能冲刺：5000 rps 入口 + 5000 张票 HELD（2026-10-31）

## 0. 测试在哪台机器上跑（先读）

- **你的 i7-1260P 本机这次没有用上。** 本机 Linux 工作区启动失败（device_bash 报 "Workspace unavailable"），所以所有测试仍在云端那台 **2 vCPU / 7 GB** 的机器上进行。
- 这台机器上同时跑着 wrk2、nginx、应用 JVM、MySQL 8.0.46 和 Redis 7，资源互相争抢。下面每张表都列出了压测工具和 nginx 实际占用的核数。
- **这些数字不能代表 i7 本机的能力，也不是最终容量结论。**
- 每次测试都有明确的超时上限：整次运行最长 1500 s，售票阶段最长 240～300 s，排空最长 90 s。一共只跑了 4 次（其中 1 次专门用于 SQL 采样），合计约 25 分钟。

## 1. 结论

| 问题 | 回答 |
|---|---|
| 5000 rps 是否真正达到？ | **网关层面达到了**：wrk2 设定 5000 rps，持续 30 s，客户端实测 **4895 rps**，共 145,555 个请求。错误、连接失败、超时**都是 0**；延迟 p50 4.9 ms、p90 92 ms、p99 297 ms。<br>**应用只处理了约 149 rps**（nginx 限到 150 r/s），其余由 nginx 直接返回 429。**这 4895 rps 是入口的抗压能力，不是下单能力。** |
| 网关和应用分别处理多少？ | 网关收到 145,555 个请求，其中约 140,900 个被 nginx 直接 429。<br>应用收到约 4,480 个（149/s）：Redis 受理 39/s（共 1,321 个 202），应用自己因容量已满返回 429 约 110/s。<br>202 之后排队超时的占 12.1%（155 个）。这次压测期间 HELD 为 31.6/s。 |
| 5000 张票是否在 30 秒内全部 HELD？ | **没有。** 在这台 2 vCPU 机器上：<br>• 中等需求（约 190 rps）下 **80.3 s** 全部 HELD，冲刺前是 123.3 s；<br>• 5000 rps 洪峰（nginx 限 100 r/s）下 **148.3 s** 全部 HELD；冲刺前没有入口限流，约 517 rps 洪峰下要 435.5 s。 |
| MySQL COMMIT 确认的 HELD TPS 是多少？ | 中等需求：稳态（第 10 秒到售罄）**63.4/s**，峰值 88.9/s，冲刺前 41.1/s，**提升 54%**。<br>5000 rps 洪峰：稳态 **34.3/s**，这期间 wrk2 占 0.38 核、nginx 约占 0.1 核。 |
| 每单资源 | 应用、MySQL、Redis 合计每单 CPU 从 **45.3 ms 降到 28.9 ms（−36%）**，其中 Java 每单从 23.3 ms 降到 13.2 ms。 |
| 正确性 | 3 次完整售票全部满足：恰好 5000 单、5000 个不同买家、MySQL 和 Redis 库存都归零、没有超出限购的用户、没有残留的 REQUESTED、没有重复锁单。每个请求的 requestId 都不同。 |
| 下一处限制 | **MySQL**：现在 mysqld 每单 13.4 ms，已经是最大的单项。具体是每单约 9 个事务、约 19 条 `SET autocommit`、约 11 条设置隔离级别的语句，以及 3 次带锁的销售计划联表查询加 3 次 SKU 读取。另外 COMMIT 平均 7.3 ms（fsync），库存桶 UPDATE 的锁等待约 15 ms。见第 6 节。 |

**粗略估算（未验证）**：按每单 28.9 ms 计算，200 HELD/s 需要约 5.8 个核分给应用、MySQL 和 Redis，压测工具另算。i7-1260P（4 个性能核、8 个能效核）有可能达到，但 Windows 上 Docker/WSL2 的磁盘 fsync 和 MySQL 的实际表现还没有测过。**30 秒卖完 5000 张能否在本机实现，必须在本机实测后才能下结论。**

## 2. 借鉴了哪些 GitHub 源码机制（读的是源码，不只是 README）

| 项目 | 读过的源码 | 采用的机制 | 落到 V3 的哪里 |
|---|---|---|---|
| PJB0911/SecKill-ii | `service/impl/CacheServiceImpl.java`（Guava 本地缓存 + Redis 两级缓存，存商品和活动）<br>`controller/OrderController.java`（`RateLimiter.create(200)` 令牌桶、固定线程池限流、`promo_item_stock_invalid_` 售罄标记） | 高频读取的商品和活动信息不走数据库；入口令牌桶限流 | **S1** 销售计划短时缓存；**S5** 网关限流（应用内已有令牌桶） |
| nageoffer/12306 | `lua/ticket_availability_token_bucket.lua`（在 Redis 里先扣"余票令牌"，再进数据库）<br>`TicketServiceImpl.purchaseTicketsV2`（Caffeine 缓存的本地锁、令牌桶刷新时的本地防重） | 先用 Redis 判断有没有余票，没票的请求不进数据库 | 只做了评估（第 5 节的 D），没有实施。V3 已经通过 Stream 串行化，不需要本地锁 |
| Goinggoinggoing/seckill | `service/impl/SeckillServiceImplv3.java`：`DECREASE_STOCK_SCRIPT`（decr 加预留计数，不足时回滚）、`localSoldOutCache`、事务消息 | Redis 预扣库存，本地缓存售罄状态 | D 的评估依据；售罄提示键列为下一步方案 |
| xiaoyeshenger/seckill | `doc/openresty/lua/seckill/reduce_stock.lua`（OpenResty 共享内存中的开始/库存标志，请求在网关就被拦截）<br>`order-service/.../stock-reduce.lua` | 在网关层拦截洪峰，不让请求进入应用 | **S5**：nginx 只对 POST 限流，返回 JSON 格式的 429 |

## 3. 修改了哪些文件（每一项都可以单独回滚）

| 编号 | 文件 | 改动 | 回滚方式 |
|---|---|---|---|
| S1 | `sale/TicketSaleArmory.java` | 新增 `cachedRuntimePlan`，按 session+sku 缓存，最长 500 ms，**只给非权威的调用方用**。每次命中都执行 1 次 Redis MGET，核对 active-version 和 publication-fence 是否等于缓存的版本，并用当前时间重新检查开售窗口。失败结果不缓存。assemble/recover 时清空本地缓存 | `ticketing.sale.plan-cache-ms=0` |
| S1 | `ticket/V3Entry.java` | HTTP 受理改用缓存：**缓存命中时入口不访问 MySQL** | 同上 |
| S1/S2 | `ticket/ReservationV2Service.java` | V3 Worker 读计划时用缓存。对刚由 T0、接管或孤儿接管拿到租约的行（fresh），**跳过 current_owned 事务和流程中的续租事务** | `-Dticketing.v3.skip-fresh-checks=false` 或环境变量 `TICKETING_V3_SKIP_FRESH_CHECKS=false` |
| S2 | `ticket/V3StreamWorker.java` | 调用 `processV3(job, true)`（2 处） | 同上 |
| S3 | `application.yml`、`docker-compose.yml` 的 `DB_URL` | Connector/J 参数：`useLocalSessionState`、`useLocalTransactionState`、`cacheServerConfiguration`、**`enableQueryTimeouts=false`**、`cachePrepStmts`、`prepStmtCacheSize=250`、`prepStmtCacheSqlLimit=2048`、`maintainTimeStats=false`、`connectTimeout=5000`、`socketTimeout=30000` | 把这些参数删掉 |
| S4 | `ticket/AttemptAdmissionException.java` | 429/503 拒绝异常不再生成堆栈（状态码、code、提示文字都不变） | 删掉那 1 个方法 |
| S5 | `ticket-risk-web/nginx/default.conf` | 只对 POST `/api/tickets/reservations` 限流：400 r/s，burst 80，返回 JSON 429；upstream 保持长连接。GET 查询不受影响 | 恢复原文件 |

- 完整改动见 `code/sprint-java.patch`。
- **没有改动**：风控规则、库存 Lua、HELD 事务内容、支付、出票、Outbox、Redis Stream 架构、MyBatis，也没有引入 Harness。

### 为什么不影响正确性

- **S1**：prepare、ordered reserve 和 HELD 三个事务仍然调用 `runtimeSku`，以 LOCK IN SHARE MODE 加锁读取装配行，并校验版本、Redis 缓存完整性、库存配置和 SKU 状态，这些都没有变。
  - 缓存可能落后的范围只有两种情况：活动在 500 ms 内被关闭（MySQL 和 Redis 版本都没变），或者其他节点修改了配置但没有换版本。
  - 这时请求会被受理，然后在事务门禁里以 `ARMORY_OR_CONTEXT_CHANGED` 终止，**不会写入库存或订单**。
- **S2**：T0 的行是在同一个线程、刚刚提交的事务里，用我们自己的 lease_token 读出来的。
  - 之后的 `decided`、prepare、reserve 和 abort 都会先 `SELECT … FOR UPDATE`，再核对 lease_token（lockOwned）。被接管时会安静退出，和原来"续租失败就直接 return"的效果一样。
  - 后台心跳线程照常每 5 秒续租一次。
- **S3 `enableQueryTimeouts=false`**：这是有意改变的超时行为，需要你知道。
  - 原来 Spring 的事务超时会给每条语句设置查询超时，5.1 版驱动为此给每条语句建一个 CancelTask。上一轮诊断里它占 Worker 采样的约 30%。
  - 关掉之后，Spring 仍然会在每条语句执行前检查事务截止时间，过期就抛 TransactionTimedOutException；但单条已经在执行的语句不再被强制取消，最长受 `socketTimeout=30000` 限制。锁等待仍受 MySQL 的 innodb_lock_wait_timeout 约束。
  - 如果不能接受，只把这一个参数删掉即可，其余参数保留。

## 4. 回归测试

| 测试 | 结果 |
|---|---|
| `V3StreamIT`（11 项：正常流程、超时栅栏、崩溃接管、COMMIT 成功但报错不补偿、Redis 状态全丢、双击别名等） | 11/11 通过（新 JDBC 参数 + 新代码） |
| `V3RedisScriptTest`、`V3CapacityTest`、`ReservationAttemptStoreTest`、`ReservationAttemptTransactionTest` | 40/40 通过 |
| 3 次 5000 张票完整售票的不变量 | 全部通过 |
| `DurableAttemptLifecycleIT`、`RedisArrivalLaneIT`（协议 3） | 在这台机器上失败。**冲刺前的原始代码、不加新参数时失败得一模一样**（4 通过 10 失败，任务一直停在 WAITING），原因是测试参数与派发配置不匹配，和本次改动无关；`TicketSaleArmoryIT` 需要 Docker，这台机器上跑不了。这几项需要在本机补跑 |

## 5. D：库存预留前移、消费者并行化

- **V3 现在已经是"Redis 先扣库存"**，只是扣减发生在 Worker 的 ordered-lua 事务里。
- 如果改成像 Goinggoinggoing 或 12306 那样在受理 Lua 里预扣：
  - 每一种排队超时、风控拒绝、版本变化、MySQL 失败都需要补偿归还库存；
  - "202 之后超时"的语义要从"没扣过"变成"扣了又还"；
  - 售罄判断会提前到受理阶段。
- 收益主要是售罄后的请求不再进入队列。这次售罄后有 278 个 SOLD_OUT 任务，约占 5%。**收益小，正确性成本大，本轮不做。**
- 等价但低风险的做法：库存 Lua 返回售罄时写一个"售罄提示"键，受理 Lua 读到就直接返回 SOLD_OUT 提示。这只是提示，不扣库存，权威结果仍以库存 Lua 为准。列为下一步。
- **消费者并行**：V3 已经是 workers=16、active_limit=16 并行消费。在 2 核上 CPU 已经接近满载（应用、MySQL、Redis 合计约 1.83 核），继续加并发不会更快。到 i7 上应该把 workers、active_limit 和连接池一起调大（第 7 节）。

## 6. 下一处限制和下一轮优化（按收益排序，还没做）

依据：售票期间的 performance_schema 语句摘要，见 `results/digest-opt1.txt`。

1. **合并 T0 和 decided**：先用 Stream 消息里的数据和缓存的计划做风控分类，再用一个事务插入已经带决策的任务行。每单少 1 个事务，约 7 条服务器端语句。风险：风控的副作用（guard 和 profile 的写入）会在接管或重复请求时多做一次，需要证明它是幂等的。
2. **售罄提示键**（第 5 节）。
3. **prepare 和 ordered 事务里的 SKU 静态行改用版本化缓存**：每单少 2 次 `SELECT * FROM tb_ticket_sku`，这条查询总耗时占比高。HELD 事务继续直接读库，保持权威校验。
4. **隔离级别切换**：RC 事务和 RR 事务交替执行，每个 RC 事务要多发 2 条设置语句。可以评估让 attempt/T0 事务改用连接池默认的隔离级别，但需要先证明在 FOR UPDATE 下语义不变。
5. MySQL 配置（本机测试时评估，不在代码里改）：`innodb_flush_log_at_trx_commit` 保持 1 不动。只检查 redo log 和 buffer pool 大小，以及 WSL2 磁盘性能。

## 7. 在 i7 本机复测需要做的准备

1. 确认 Docker Desktop 的资源设置：WSL2 默认能用全部 16 个逻辑处理器，内存上限按 `.wslconfig` 的设置。
2. 用 cpuset 把应用和 MySQL/Redis 限定在一组核上，wrk2 用另一组核。例如应用加 MySQL 用 0–11，wrk2 用 12–15。资源争抢要记录下来。
3. 应用参数：`JAVA_TOOL_OPTIONS=-Xms2g -Xmx2g`；`ticketing.reservations-v3.workers`、`active_limit`、`ticketing.pools.sale.max` 设为 48～64；Hikari 连接池 64；nginx 的 rate 按实测 HELD 能力的 2～2.5 倍设置。
4. 场景 A：wrk2 `-R5000 -d30s` 打 nginx，统计网关收到多少、nginx 拒绝多少、应用收到多少、Redis 受理多少、202 之后超时多少。
5. 场景 B：预置 5000 张库存和至少 6 万个有效用户，从开售时刻计时到第 5000 笔 HELD 提交。分别在中等需求（每秒 300～400 次）和 5000 rps 洪峰下各跑 1 次，不展开多轮矩阵。
6. 用的就是这次的压测工具（`CapacityExperimentIT` 的 flood 和 sellout 模式，见 `results` 里的工具包）。需要在本机跑一个 MySQL 13306 实例和一个 Redis 6390 实例；**不会动现有业务容器里的数据。**

## 8. 文件

- `code/`：
  - 5 个 Java 文件（CRLF 换行）
  - `application.yml`
  - `docker-compose.yml`
  - `default.conf`
  - `sprint-java.patch`
- `results/sprint-runs.tgz`：
  - 4 次运行的 capacity.json（逐秒 HELD、CPU、不变量）
  - wrk 原始输出
  - SQL 摘要
  - 冲刺前的两个基线
