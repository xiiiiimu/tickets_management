# 同 SKU / 16 SKU A/B 实验

## 配置与预热

- 当前代码，同一 Java 8 进程、MySQL 8 / Redis 7 / RocketMQ 4.9.7；32 clients，160 requests，每轮 160 个不同用户、不同请求 ID、每人一张票。
- A：全部请求 SKU-001；B：16 个 SKU，每个 SKU 恰好 10 个测量请求。两组都存在同样的 16 个 SKU，每个库存 1000。
- 风控固定 PASS，限流高于测试负载；自动支付、出票、工作流和后台任务保持开启。
- JVM -Xms128m -Xmx384m -XX:+UseSerialGC；Hikari 上限 10。埋点和连接池采样前后完全相同，没有调整交易逻辑。
- 正式测试前预热 4 批 ×160 = 640 请求；至少四批，最后两批 QPS 和 Avg 相邻变化均不超过 15%。
- 每轮重新建立相同测试数据，并用独立预热用户对所有 16 个 SKU 各请求一次、等出票完成，确保 Redis 库存配置就绪。预热请求不计入测量。
- 执行顺序 A1 → B1 → B2 → A2 → A3 → B3；全程同一服务进程，不在测量期间运行构建。
- 最大客户端并发为 32；160 个请求是有限批次的闭环负载，没有测最大持续容量。

## HTTP 请求结果

QPS 为三轮中位数；Avg/P95/P99 从每组全部 480 个客户端请求耗时计算。客户端耗时不包括客户端线程池排队，也不包含后台完成出票的时间。

| 实验 | QPS 中位数 | Avg ms | P95 ms | P99 ms | 成功/出票 |
|---|---:|---:|---:|---:|---:|
| A | 5.41 | 5385.36 | 7815.54 | 8356.83 | 480/480 |
| B | 8.02 | 3820.92 | 5355.06 | 6830.79 | 480/480 |

## Controller 同步阶段

均从每组 480 条服务器请求日志计算。嵌套阶段与父阶段不可重复相加。

| 阶段 | A Avg ms | A P95 ms | B Avg ms | B P95 ms |
|---|---:|---:|---:|---:|
| request.lookup | 464.442 | 1359.097 | 28.588 | 134.607 |
| risk.admit | 2563.022 | 4600.501 | 3116.941 | 4679.368 |
| sku.exists | 122.387 | 820.986 | 6.576 | 14.715 |
| inventory.ensureReady | 5.830 | 17.171 | 10.380 | 33.881 |
| intents.create | 552.144 | 1343.334 | 183.615 | 336.469 |
| transactions.reserve | 1631.262 | 2561.317 | 403.010 | 717.757 |
| total | 5339.164 | 7778.721 | 3749.225 | 5283.168 |
| http.total | 5350.524 | 7789.070 | 3770.045 | 5300.485 |

## intents.create 内部

**当前源码没有 SKU FOR UPDATE。SKU 使用普通 SELECT；加锁阶段为未执行，不能填成一次零耗时的加锁。** 960 条测量请求中 sku.for_update 调用次数均为零。

| 阶段 | A Avg ms | A P95 ms | B Avg ms | B P95 ms |
|---|---:|---:|---:|---:|
| intents.create.tx_begin | 476.806 | 1266.631 | 21.628 | 141.336 |
| intents.lookup | 1.673 | 5.978 | 2.599 | 10.858 |
| intents.sku_read | 2.249 | 9.423 | 2.790 | 12.542 |
| intents.check | 0.156 | 0.273 | 0.151 | 0.223 |
| intents.insert | 3.593 | 11.566 | 6.444 | 16.154 |
| intents.readback | 2.115 | 9.131 | 3.136 | 12.705 |
| intents.create.commit | 62.971 | 159.835 | 143.521 | 260.438 |

tx_begin 是 DataSourceTransactionManager.doBegin 的实测耗时，包含获取连接和事务准备，不能当作纯连接池等待；commit 测量实际 doCommit。INSERT 阶段还包含本次插入参数的指纹与 ID 生成。

## transactions.reserve 的库存更新

| 阶段 | A Avg ms | A P95 ms | B Avg ms | B P95 ms |
|---|---:|---:|---:|---:|
| transactions.reserve.tx_begin | 90.786 | 622.747 | 4.468 | 11.407 |
| reserve.stock_update | 1333.344 | 1991.443 | 5.416 | 15.107 |
| transactions.reserve.commit | 59.363 | 149.088 | 147.709 | 273.830 |

reserve.stock_update 对应以下 UPDATE 的整个 JDBC 调用耗时，包含等待行锁、SQL 执行、网络与驱动开销，不能全部当作纯锁等待：

```sql
UPDATE tb_ticket_sku SET remaining_stock=remaining_stock-?,version=version+1
WHERE id=? AND remaining_stock>=? AND status='ON_SALE'
AND sale_start_time<=CURRENT_TIMESTAMP AND sale_end_time>CURRENT_TIMESTAMP;
```

## 负载期间连接池与 MySQL 行锁

Hikari MXBean 每 200ms 直接采样，按客户端负载开始/结束时间筛选日志；表中为采样峰值与均值。避免在负载期间抓取全量 Prometheus，因为现有应用的全量抓取会触发数据库统计查询。

| 实验 | active 峰值 | pending 峰值 | active 均值 | pending 均值 | 行锁等待总 ms | 等待次数 |
|---|---:|---:|---:|---:|---:|---:|
| A | 10 | 26 | 9.28 | 18.27 | 641497 | 484 |
| B | 10 | 26 | 6.40 | 1.21 | 2042 | 16 |

MySQL 行锁计数为 Innodb_row_lock_time / Innodb_row_lock_waits 的每轮增量，窗口包含测量请求及其后台出票完成；是整个隔离数据库的统计，不是只针对某条 SQL 的统计。

## 结论

分散到 16 个 SKU 后，QPS 中位数从 5.41 升到 8.02（约 +48.3%）。库存 UPDATE 的平均 JDBC 耗时从 1333.34ms 降到 5.42ms；三轮累计数据库行锁等待从 641497ms 降到 2042ms。实验支持同 SKU 的库存行锁争用是一个重要瓶颈，争用发生在 transactions.reserve 的库存 UPDATE，当前 intents.create 不执行 SKU FOR UPDATE。

单 SKU 时 intents.create 的 552.14ms 中，476.81ms 位于开始事务阶段（获取连接及事务准备），内部查询、检查和插入都是毫秒级。多 SKU 将平均 pending 从 18.27 降到 1.21，但两组都曾采样到 26 的峰值，不能仅凭峰值判断等待严重程度相同。

多 SKU 下 risk.admit 平均 3116.94ms，占 Controller total 约 83.1%。它包含特征采集、Redis、数据库和规则计算，本实验没有进一步分解这些内部耗时，不能将全部时间归因于规则 CPU 或某个具体 I/O。结果仅说明这组本地有限批次负载中的差异。

## 逐轮原始结果

| 轮次 | QPS | Avg ms | P95 ms | P99 ms | active 峰值 | pending 峰值 | 行锁等待 ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| A1 | 5.16 | 5469.61 | 7796.69 | 8905.57 | 10 | 26 | 216072 |
| B1 | 7.30 | 4111.84 | 5425.20 | 6902.06 | 10 | 10 | 1058 |
| B2 | 8.25 | 3650.10 | 4626.52 | 6481.26 | 10 | 7 | 388 |
| A2 | 5.41 | 5383.78 | 8002.62 | 8356.83 | 10 | 26 | 216818 |
| A3 | 5.47 | 5302.67 | 7516.52 | 8355.64 | 10 | 26 | 208607 |
| B3 | 8.02 | 3700.80 | 5308.91 | 6769.77 | 10 | 26 | 596 |

## 每请求数据与复现

- results/sku-ab/A-1.json 等文件包含每个 requestId 的客户端耗时、完整服务器分段计时、SKU、连接池时间序列及库存校验结果。
- A 库存：SKU-001 剩 839，其他各 999；B：16 个 SKU 各剩 989（每 SKU 均包含 1 张预热票）。每轮实际分布、订单数和出票数都由数据库验证。
- 相关回归测试通过：预订幂等 4 项、事务回滚 3 项、库存初始化 2 项。实际实验额外校验每个请求都有一次 intents.create 和 transactions.reserve 的真实提交计时。
- 运行当前包时开启参数：--ticketing.perf-log.enabled=true --ticketing.perf-log.pool-interval-ms=200；JVM 时区设为 UTC 以对齐日志和客户端采样窗口。平时计时默认关闭，原连接池日志默认间隔仍为 5000ms。

```powershell
Set-Location E:\credit_risk
python .\performance\compare_sku_contention.py
```

脚本只操作 credit-risk-ab 隔离容器，服务地址为 http://127.0.0.1:18082。
