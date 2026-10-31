# TicketSaleArmory 性能基线与瓶颈诊断

日期：2026-10-28。本轮没有修改生产 Java、Lua、订单状态机或业务配置，没有开展 LOW 或动态规则开发。

## 结果

同一套环境、32 并发、160 请求、单 SKU、16 个真实库存桶，A 关闭 Armory，B 开启且完成 READY 装配。每轮独立数据库和 Redis 逻辑库，三批各 160 请求预热，另用测量 SKU 做一笔目录预热。测量 SKU 总库存 1000，测量前可用 999，最终 839。预热 SKU 与测量 SKU 分开。

执行顺序 A1 → B1 → B2 → A2 → A3 → B3。主对照保留 Mock 支付、出票、MQ、Outbox、工作流及后台任务。

| 组 | QPS 中位数 | P50 ms | P95 ms | P99 ms | 成功正式订单 | 错误 |
|---|---:|---:|---:|---:|---:|---|
| A：关闭 | 10.93 | 2536.11 | 3834.63 | 4583.54 | 480/480 | 0 |
| B：开启 | 8.18 | 3637.50 | 6537.19 | 8091.45 | 480/480 | 0 |

观察到 QPS 中位数下降 **25.2%**，P95 增加约 **70.5%**。延迟分位数合并各组 480 请求计算；QPS 是三轮中位数，二者不是同一聚合方式。

| 轮次 | QPS | P50 ms | P95 ms | P99 ms | 成功订单 |
|---|---:|---:|---:|---:|---:|
| A1 | 10.083 | 2713.69 | 4392.95 | 4902.12 | 160 |
| B1 | 6.550 | 4707.58 | 6941.92 | 8091.45 | 160 |
| B2 | 8.336 | 3665.82 | 5599.74 | 6047.45 | 160 |
| A2 | 10.934 | 2804.13 | 3644.95 | 4306.32 | 160 |
| A3 | 14.563 | 2118.01 | 3054.47 | 3716.93 | 160 |
| B3 | 8.179 | 2939.27 | 6860.83 | 8408.77 | 160 |

正式轮没有 HTTP/业务拒绝、库存耗尽、限流、黑名单或画像阻断、重复 requestId、Agent 调用。各轮 `risk_fast_allow_total` 增量 160。启用全部 11 条特征规则，仅专用测试库调高共享 IP 阈值与 rateLimit；全部新测量用户无历史订单、设备独立、账号年龄足够，固定 PASS，不代表真实用户风险分布。

**可比性限制：**关闭组 10.08–14.56、开启组 6.55–8.34 QPS，波动明显；固定三批预热没有证明 JIT/主机已达到稳态。每轮重启独立 JVM，A1 排空后等待 1 秒，后续轮为保证 MQ offset 落盘等待 6 秒。这些数据支持本机小批量回归诊断，不构成稳定容量承诺或精确因果分解。三对 B/A 的方向一致，但只有三轮，尾延迟样本少。

## 同步阶段

以下均为主对照每组 480 请求的平均毫秒数。嵌套阶段不能相加。

| 阶段/方法 | A 关闭 | B 开启 | 解释 |
|---|---:|---:|---|
| RiskDecisionService.admit | 1203.73 | 1590.88 | 含特征、画像、规则执行及其连接等待 |
| RiskFeatureService.collect | 445.46 | 572.32 | 含 Redis 特征与四次历史/账号 SQL |
| ReservationIntentService.create | 342.73 | 565.37 | RequestTimings，含新事务和提交 |
| intent 事务 begin | 238.95 | 331.22 | 含连接获取，不能全当锁等待 |
| intent commit | 40.24 | 37.91 | 客户端提交耗时 |
| ReservationTransactionService.reserve | 615.36 | 832.36 | 含事务、Lua、SQL、工作流、提交 |
| reserve 事务 begin | 99.16 | 149.28 | 含连接获取 |
| reserve commit | 48.44 | 43.21 | 客户端提交耗时 |
| Redis 库存预占 | 16.55 | 14.81 | 客户端 Lua 包围耗时，不是纯服务端 CPU |
| MySQL 库存扣减及分配 INSERT | 55.64 | 59.58 | 含 SQL 执行及锁竞争，不是纯行锁时间 |
| sale_admission | — | 268.21 | 独立短事务，包含连接获取及提交 |
| sale_intent_gate | — | 116.77 | 在意图事务内执行 |
| sale_reserve_gate | — | 112.73 | 在落单事务内执行 |

三个 gate 在 B 各有 480 次成功方法调用。每轮两个事务内 gate 的 P95 均落在 Micrometer **100–500ms** 桶，sale_admission 在 **500–1000ms** 桶；这是分位范围。精确的 HTTP、意图与落单事务分位数在 `analysis.json`，不能将粗直方图当作精确方法 P95。

## 按证据排序的瓶颈

### 1. 连接满载与事务持有时间，证据最强

Hikari 固定 10。A 每轮 pending 样本均值约 18.24/18.41/18.69，B 约 20.57/20.14/17.85，峰值 25–28。连接获取累计：A 6471 次/764.28 秒，平均 **118.11ms/次**；B 6456 次/1237.02 秒，平均 **191.61ms/次**。次数接近，等待变长，不能简单解释为 B 多申请了很多连接。

这些是抓取窗口内全池数据，含后台线程及抓取边界工作，不能直接除以请求数当作单个 HTTP 的等待。MXBean 每 200ms 记录，最长采样间隔 A 0.55s、B 1.14s；样本均值不是严格时间加权均值。

建议先减少事务中的重复读和往返，再单因素试验池大小 10→16，观察 HTTP、锁等待与出票排空时间是否同时改善。不要直接扩大到 32/64，也不要提前提交库存或破坏订单/Outbox 原子性。

### 2. 三次完整 gate 与重复 SKU/桶查询，直接可优化

[TicketSaleArmory.gate](E:/credit_risk/ticket-risk-platform/src/main/java/com/ticketing/sale/TicketSaleArmory.java:254) 每次读取版本、发布 fence、manifest、库存键存在性、SKU fence/config，并解析 JSON、计算摘要、查询 SKU、桶余量及活动状态。一个正常请求执行三次。

[findById](E:/credit_risk/ticket-risk-platform/src/main/java/com/ticketing/repository/jdbc/JdbcTicketInventoryRepository.java:24) 在桶激活时额外 SUM 库存。A 原有两次完整 SKU+SUM；B 加三个 gate，达到五次，此外两组各有 controller 的存在性查询。三轮桶 SUM digest 增量约 A 963、B 2404，包含少量后台刷新。B 活动可售查询约 1438 次。digest 不是原子快照，存在少量计数边界误差。

**建议下一轮只选一项最小改造：同一事务内复用 gate 已校验的 SKU，消除意图和落单紧接着的两次重复 SKU+SUM，共约四次 SQL/请求。** 保留三个 gate、READY、实时状态/时间校验、版本 fence、Redis 缺失拒绝、最终库存 CAS、幂等及限购。不跨事务缓存可变 SKU 或库存，不改外部 API/Lua。改后重复本轮 A/B，才能确认收益；本轮未实施。

### 3. 后台共享池与工作流写放大，有证据但不能精确拆账

主对照请求窗口内 workflow 相关 digest 三轮约 A 10776、B 10769 条，平均约 22 条/正式请求，但其中也包含同期后台工作，不能全算同步 SQL。[同步落单](E:/credit_risk/ticket-risk-platform/src/main/java/com/ticketing/ticket/ReservationTransactionService.java:62) 确实执行两次 workflow.record；其内部包含初始化、节点、检查点和状态写入。应保留持久化语义，评估合并重复 SELECT/UPDATE，不能直接删除审计。

另补充 **不启用 JFR/锁轮询** 的后台暂停诊断：

| 诊断组 | QPS | P50 ms | P95 ms | P99 ms | 接受订单 |
|---|---:|---:|---:|---:|---:|
| A-quiet | 12.72 | 2297.53 | 3444.60 | 3979.58 | 160/160 |
| B-quiet | 10.13 | 3045.92 | 4341.93 | 4876.26 | 160/160 |

关闭既有 jobs/mq 开关，保留正常订单和 Outbox 写入，没有删除交易步骤。相比完整履约组中位数数字上分别高约 16.4%/23.8%，**不是可承诺收益**：各仅一轮、预热订单为 WAITING_PAYMENT，工作流/支付表行数及状态与主对照不同，且停掉的是多种后台任务，不能归因于单独 Mock 支付。

主轮在 HTTP 批次结束后的稍后观察仍有大量 PAID/WAITING_PAYMENT，最终每轮 641 单全部 ISSUED；`sql_after_sync` 到 `sql_after_drain` 的差值单独保留。可考虑独立有界的发布/履约执行器和后台数据库连接预算；MQ 消费上限目前为 16，与 10 个共享连接不匹配。调整必须同时看积压和最终出票延迟，不能只改善 HTTP 数字。

### 4. MySQL 提交、库存桶锁与存储等待，需要环境复测

主轮 COMMIT 服务端累计 A 38.87s/1325 次，B 36.23s/1828 次，包含后台及 B 的只读事务。不能将这些累计并发时间当作墙钟时间。请求内提交均值约 38–48ms，确有成本，但不能把整个同步链路解释为提交。

额外深度诊断的 data_lock_waits：A 10 次采样中 3 次有等待，共 4 条；B 13 次中 3 次，共 5 条，均为 `tb_ticket_stock_bucket / PRIMARY / X,REC_NOT_GAP`。未捕获 assembly 等待。采样稀疏，不能据此证明永不等待。

深诊断观察窗口约 36.18/43.46s，redo 文件累计等待约 32.00/38.79s，数据文件约 11.82/12.07s，doublewrite 约 6.02/6.87s，binlog 约 4.83/3.58s。这些为全实例、多线程、含窗口边界的累计等待，不是 fsync 独占时间或每请求延迟。MySQL 使用 E 盘 Windows bind mount，E 盘约 96% 已用，是需要先改善的环境条件。保持 `innodb_flush_log_at_trx_commit=1`、`sync_binlog=1`，未降低持久性换 QPS。

建议缩短桶扣减后到提交之间的连接与锁持有时间；后续在空间充足、较稳定的存储环境复测。保留 16 桶及一致性协议，不重写库存。

### 5. 历史特征与画像查询：先减少往返，未证明大范围聚合扫描热点

[collect](E:/credit_risk/ticket-risk-platform/src/main/java/com/ticketing/risk/RiskFeatureService.java:16) 每请求读取账号、订单聚合、最近支付状态、已知设备支付统计；画像缺失还导致 USER/DEVICE/IP 三次数据库读取。两组各三轮支付查询约 960 次、画像查询约 1440 次。本轮新用户历史为空，不能代表大历史用户聚合开销。

可以评估一次取数/有界历史索引、事务后更新的聚合特征、带版本与失效机制的缓存。负缓存和历史聚合缓存可能延迟 BLOCK 生效或降低特征新鲜度，需先定义一致性及失效方案，不得直接跳过安全检查。本轮没有改变裁决语义。

### 6. 内存快照可做，但不能掩盖 Redis 缺失或篡改

可按 session/version/digest 保存有界不可变 SaleManifest，预解析 SKU 索引及规则配置，避免每请求三次 JSON 深冻结。RiskRule bean 可变，不能直接跨请求共享可变规则对象。B 已使用冻结规则，主轮没有再次查询全局 tb_risk_rule；重复的是包含规则的 manifest 读取/解析，并非三次全局规则 SQL。

缓存解析结果不自动等于可删除 Redis 往返。若跳过 manifest 读取，可能掩盖键缺失/内容篡改。第一步可保留原字节读取、版本/fence/摘要及配置校验，只复用同字节解析结果；再评估同 slot 的批量读或 pipeline。不要把库存、限购计数、实时销售状态永久放进静态缓存。Lua 客户端均值仅约 15–17ms，当前没有证据优先改写库存 Lua。

## 监控检查与观测限制

- RequestTimings 按请求 ID 关联；BusinessMetricsAspect 使用固定 operation/outcome 标签，方法 count/sum 可计算准确窗口均值。operation success 表示方法未抛异常，不替代订单成功校验。
- [PlatformMetrics](E:/credit_risk/ticket-risk-platform/src/main/java/com/ticketing/infrastructure/PlatformMetrics.java:29) 每 15 秒独立执行三条汇总 SQL，Prometheus 抓取读取内存，不在抓取中逐次聚合数据库。Hikari MXBean 不查 SQL。主测试仅前后抓取 Micrometer，不热循环抓全量指标；没有证明监控开销为零。
- 现有 Prometheus/Grafana 配置保留：开发目标为 host.docker.internal:18082、数据源 ticket-prometheus。本次 Java 使用 18083，原始 Micrometer 文本单独保存，**没有本轮 Grafana 连续曲线**，不把开发目标数据冒充本轮数据。
- 深度采样 A-deferred/B-deferred 分别仅 6.15/4.24 QPS，观测扰动明显；不并入性能对比。JFR 保留原始记录，导出栈深不足时的 park 不强行归因为 Hikari/Redis。MySQL SocketRead 是客户端等待服务端响应，也不是纯 SQL CPU 时间。
- 性能测试工具仅做 Python 语法检查；当前 Java 构建 `mvn package -DskipTests` 成功。本轮没有重新运行全部 JUnit，不将旧测试数量冒充本轮执行结果。

## 历史 9.52 QPS

[历史报告](E:/credit_risk/performance/SKU_ABC_REPORT.md) 的单 SKU/16 桶为 9.52 QPS，本轮关闭/开启分别在数值上高约 14.8% / 低约 14.1%。不能据此宣称相对历史优化或回退：历史 Java 8 在 Docker，本轮 JDK 21 本地运行；风控启用情况、SKU/目录预热数量、数据、源码、存储路径、后台负载与监控实现不同。应以本轮开关对照定位 Armory 成本。

## 复现、原始证据与回滚

操作命令及测量定义见 [方法文档](E:/credit_risk/performance/ARMORY_BASELINE_METHOD.md)。固定 JVM 128–384MB、SerialGC、Hikari 10；MySQL 8.0.32、Redis 7.2、RocketMQ 4.9.7。jar SHA 与 JDK 版本在 runtime-identity.json。

原始目录：**E:\credit_risk\performance\results\armory-20261028_112739**。

- `analysis.json`：逐轮及聚合结果、精确请求阶段分布、方法计数/均值、SQL 增量、连接等待。
- `<轮次>-requests.json`、`<轮次>-warm0/1/2.json`：逐请求响应、耗时及预热原始数据。
- `<轮次>.stdout.log / stderr.log`、`<轮次>-before.prom / after.prom`：服务器日志及指标。
- `<轮次>-diagnostics.json`：数据库名、Redis 库、库存、SQL before/sync/drain、履约状态。
- `A/B-deferred.jfr`、`*-lock-samples.json`、`*-io-before/after.json`、`jfr-analysis.json`：深度诊断证据；不作为主吞吐对照。
- `final-data-audit.json`：十轮数据库验证；190 个业务源码/配置文件未变化，Git 索引未变化。主组每轮 641 单全部出票；四个后台暂停组每轮 641 单按预期 WAITING_PAYMENT。每轮测量 SKU 161 笔分配、839 余量、单用户购买量 1。
- `mq-final-progress.txt`：测试 MQ 无积压。`restart-policy-final.txt`、`docker-ps-final.txt`、`docker-ps-a-final.txt`：八个本项目容器全部 exited、restart=no。

失败尝试保留且未混入统计：`performance/results/armory-20261028_111742` 为原开发 MQ 因 E 盘 96% 使用率拒绝发送，只有预热；正式环境使用全新 MQ 数据卷保持保护阈值。B1 曾在启动阶段 NameServer 超时，重试前零业务请求，失败启动日志另存。诊断库名错误发生于建库前，修复后重新创建。未以失败尝试选择性剔除有效性能轮。

新增独立测试资源：credit-risk-bench-namesrv、credit-risk-bench-broker、credit-risk-bench-mq-network、credit-risk-bench-mq-store；均保留，默认停止。原开发数据库 credit_risk_dev 未修改，DB0 未写入；其他项目容器没有停止、重建或清理。

新增文件为 armory_baseline.py、analyze_armory_baseline.py、analyze_armory_jfr.py、observe_armory_io.py、armory-mq.compose.yaml、armory-broker.conf、本报告和方法文档，均在 performance 下。结果目录已被现有 Git 忽略规则覆盖，真实密码仅在忽略的 .env 中。

回滚无需改生产代码：保持两个 Compose 项目停止即可；工具和文档可单独撤回。测试库、Redis DB1–DB11（DB1 为失败预热）、容器与数据卷不自动删除。没有设置开机任务或自动启动。
