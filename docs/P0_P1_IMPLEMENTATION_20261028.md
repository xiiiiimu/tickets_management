# P0 正确性与 P1 核心观测实施记录

日期：2026-10-28。范围仅第一批；下一批优先 TicketSaleArmory，之后才是活动动态策略与 LOW。

## 工作区保护与交付单元

本轮起点是已有大量未提交修改的工作区，而非 Git HEAD。未执行 reset、clean、stash、批量暂存或提交；避免把此前工作混入本轮提交。原始文件、原索引差异及工作区差异保存在 `.tmp/p0-p1-20261028/`。按起点生成的四个增量 patch 和 manifest 可分别评审；它们依赖起点工作区，不是对裸 HEAD 的补丁。实际未创建 Git commit。

1. 01-inventory：事务初始化、提交后发布、SETNX、保守缺失拒绝及其测试。
2. 02-agent：响应契约、执行状态、纯裁决、无损持久化及其测试。
3. 03-integration：调查时序、支付出票重放、初始化提交/回滚、真实指标接入；测试镜像、时区和旧 JDBC 驱动配置。
4. 04-observability：业务阶段直方图、内存快照、Prometheus 单一采集目标、可选 Grafana、开销基准。

## 01：库存配置边界

修改 `InventoryService.java`，使用 TransactionTemplate 保证 ensureReady 内部调用也有真实事务。已有外层事务时注册提交回调，回滚不发布；提交后通过现有 cache 池发布。没有外层事务时，先完成事务并释放连接，再同步发布。发布重新获取 SKU 锁并读取最新配置，防止旧回调重新发布旧售卖状态。

普通库存使用 SETNX，不覆盖已有剩余量；保留“库存未动且无预占意图”初始化条件。已有交易而 Redis 库存丢失时不恢复初始票数，ensureReady 显式拒绝。未修改任何库存 Lua、Key、库存桶扣减/释放算法、订单状态机或交易接口。

发布失败不把已提交事务伪装为回滚，记录 inventory.config.publish.failure，由既有定时配置刷新重试。管理配置提交与 Redis 生效之间存在短暂窗口，SQL 售卖状态/时间检查继续最终把关；Redis 预占后的 SQL 拒绝仍走现有补偿。jobs 被禁用时没有自动定时重试，运维需重试 configure；这不是 READY 或灾难恢复协议。

风险：无数据库与 Redis 跨库原子承诺；发布 Redis 时仍可能部分失败。缺失交易缓存保持拒绝，不自动重建用户限购、幂等标记。完整多实例装配代次、READY、恢复屏障属于下一批。

## 02：Agent 与裁决

新增 AgentExecutionStatus、不可变 AgentAnalysis、纯函数 RiskAdjudicator。RiskAgentClient 保留原 Map 方法作为适配边界，生产 HTTP 结果必须先通过类型、枚举、范围、有限数值、整数调整、理由列表校验。区分 NOT_CONFIGURED、UNAVAILABLE、TIMEOUT、CONNECT_ERROR、HTTP_ERROR、INVALID_RESPONSE、ERROR；明确 PASS/BLOCK 不调用为 NOT_CALLED。

保留 400ms 连接、2s 读取预算及 Python 图。新增 executionStatus 是内部结果字段；现有字段和公开调用签名保持。persist 改用 get，不再删除 phase/features/analysis；工作流保留完整调查结果并携带 traceId，规则版本沿用 matchedRules 的 code@version。

兼容策略：仍允许低置信度的合法正向调整跨越 BLOCK；仍忽略负调整；高置信度 HIGH/LOW 行为不变。建议后续把低置信度跨 BLOCK 改为待复核、负调整只在明确证据条件下接受，但两项均需业务确认后另做版本。本轮未实施这些行为变化。confidence 为模型自报确定程度，不解释成概率。

风险：Python aiAvailable=false 未提供机器可读的细分失败状态时，Java 只能归为 UNAVAILABLE。2s 客户端超时不会保证取消 Python 正在执行的 LLM 工作。并发调查仍可能重复 HTTP 调用；数据库副作用继续依赖现有消费幂等。

## 03：集成测试

原调查测试在未出票时发送 RISK_CHECK，与现有前置条件冲突。拆分为支付→出票→重复出票/调查，以及独立的未支付预订重复释放用例，避免对已出票订单直接回补库存。新增提交前不发布、回滚不发布、提交后生效、丢失交易库存拒绝和真实 Spring 业务指标接入检查。

MySQL 连接明确旧驱动 com.mysql.jdbc.Driver、serverTimezone=UTC、useLegacyDatetimeCode=false，避免 JVM 与数据库时区歧义。旧 Connector/J 5.1 对 MySQL 8 空参数元数据探测会抛 NPE，测试 JVM 显式忽略该探测；保留 null 测试值，未升级生产驱动。镜像通过 ticketing.test.mysql-image / ticketing.test.redis-image 覆盖，默认标签不变；本机验证使用已有 mysql:8.0.32 与 redis:7.2.0。Docker 29 配合旧客户端时命令增加 api.version=1.44。

## 04：核心观测

新增 BusinessMetricsAspect；固定 operation/outcome 标签覆盖准入（含钱包 fast）、特征、调查、Redis 预占、MySQL 库存扣减、订单事务、自动支付、消息消费与释放。事务切面外计时包含连接获取与提交。outcome 表示方法正常返回或抛异常，不把返回 false 的 SQL 条件更新误称为交易成功。

Agent HTTP 增加耗时和分类执行计数。Hikari 获取连接与 HTTP 抢票启用直方图；保留现有池、工作流与库存计时。没有 userId/requestId/orderId 标签。

PlatformMetrics 改为每实例每 15 秒三条数据库查询采样，抓取只读内存。首次采样前为 NaN；失败保留旧值，暴露 snapshot.age、snapshot.failure、snapshot.duration。桶模式 stock.remaining 仍是 SKU 聚合投影，不代表即时权威桶余量。大数据量下采样 SQL 仍需实测与索引评估。

Prometheus 默认只抓容器目标 ticket-risk-platform:8082，避免主机与容器同服务重复采集。本机直跑 Java 时应另选主机目标，不能同时重复配置。Grafana 使用 observability profile，持久化数据源及 12 面板看板，默认只绑定 127.0.0.1:3000；未启动或替换用户现有服务。

启动可选观测服务：配置 GRAFANA_ADMIN_PASSWORD 后执行 docker compose --profile observability up -d prometheus grafana。访问 http://localhost:3000，用户名 admin。不要使用 compose down -v。

## 验证命令与结果

见本节末尾的实际结果。Java 在 JDK 21 上以项目 Java 8 target 编译；未声称已在 Java 8 运行时验证。

```powershell
# E:\credit_risk\ticket-risk-platform
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'
mvn -o test
mvn -o -Pintegration verify '-Dtest=*Test,MonitoringOverheadBenchmark' '-Dspring.jdbc.getParameterType.ignore=true' '-Dapi.version=1.44' '-Dticketing.test.mysql-image=mysql:8.0.32' '-Dticketing.test.redis-image=redis:7.2.0'
# E:\credit_risk\risk-agent
$env:PYTEST_DISABLE_PLUGIN_AUTOLOAD='1'
.\.venv\Scripts\python.exe -m pytest -p pytest_asyncio.plugin -q -p no:cacheprovider
# E:\credit_risk
docker compose --profile observability config --quiet
```

基线：Java 96 项、95 通过、1 Docker 跳过；受限环境集成启动失败。Python 35 通过。放开本机 Docker 访问后运行真实隔离容器，不修改已有业务数据。首次真实 MySQL 暴露旧测试时区歧义、完整上下文暴露测试驱动类配置缺失，已限定修复于测试。开发中一次并发构建引起 class 文件竞争，已改为顺序完整回归；不将该次结果计入验收。

## 回滚

无需数据库迁移，无 Lua/状态机/公开 API 回滚。可先关闭 ticketing.metrics.business.enabled 和 ticketing.metrics.snapshot.enabled，停止新增 Grafana 即可停用观测增量。

代码回滚应针对本轮起点的增量，禁止 git reset --hard 或 git clean。使用交付目录内 rollback.py --stage <inventory|agent|integration|observability>；默认仅预览，添加 --apply 才执行。脚本先校验所有目标与本轮完成时哈希一致，存在后续修改则拒绝，避免覆盖新工作。它恢复原有文件的原始字节，只删除本轮新增文件，不碰其他文件或数据库。

下一批：TicketSaleArmory，优先完成装配状态、不可变版本、READY 校验、复用现有库存的幂等初始化、多实例发布保护与安全恢复。LOW 与活动动态策略不得抢先实施。

## 本轮实际文件清单

| 单元 | 文件（仓库相对路径） |
|---|---|
| 库存 | ticket-risk-platform/src/main/java/com/ticketing/inventory/InventoryService.java |
| 库存测试 | ticket-risk-platform/src/test/java/com/ticketing/inventory/InventoryEnsureReadyTest.java；InventoryConfigurationTransactionTest.java |
| Agent | ticket-risk-platform/src/main/java/com/ticketing/risk/RiskAgentClient.java；AgentAnalysis.java；AgentExecutionStatus.java；RiskAdjudicator.java；RiskDecisionService.java |
| Agent 测试 | ticket-risk-platform/src/test/java/com/ticketing/risk/AgentBoundaryTest.java；RiskDecisionServiceTest.java |
| 集成测试 | ticket-risk-platform/src/test/java/com/ticketing/integration/TicketPlatformIT.java；src/test/java/com/ticketing/inventory/InventoryCasMysqlConcurrencyTest.java |
| 核心观测 | ticket-risk-platform/src/main/java/com/ticketing/config/BusinessMetricsAspect.java；src/main/java/com/ticketing/infrastructure/PlatformMetrics.java；src/main/resources/application.yml |
| 观测测试 | ticket-risk-platform/src/test/java/com/ticketing/config/BusinessMetricsAspectTest.java；MonitoringOverheadBenchmark.java；src/test/java/com/ticketing/infrastructure/PlatformMetricsTest.java |
| 部署配置 | docker-compose.yml；docker/prometheus.yml；docker/grafana/provisioning/datasources/prometheus.yml；docker/grafana/provisioning/dashboards/ticketing.yml；docker/grafana/dashboards/ticketing.json |
| 说明 | docs/P0_P1_IMPLEMENTATION_20261028.md |

精确路径、文件是否原有及完成时 SHA-256 同时保存在交付目录 manifest.json。

## 最终实际验证结果

- Java 常规回归与显式微基准：111 项通过，0 失败、0 错误、0 跳过。其中常规测试 110 项，基准 1 项。
- 完整 Spring + MySQL 8.0.32 + Redis 7.2.0 集成：10 项通过，0 失败、0 错误、0 跳过。最终 Maven verify：BUILD SUCCESS。
- Python 原有服务未修改：35 项通过，2 条依赖弃用警告。
- docker compose --profile observability config --quiet：通过。未启动 Grafana，因此不声称已完成运行中看板验收。
- 库存单元：冷路径实际事务、已有库存不覆盖、回滚不发布、最新配置发布、缺失交易库存拒绝、两个实例并发 SETNX、发布失败等针对性测试通过；真实数据库集成也覆盖提交/回滚与缺失拒绝。
- Agent 单元：非法响应、数值边界、不可变响应、运输错误分类、原裁决兼容及无损持久化测试通过；保留原交易与画像语义回归。
- 指标单元：真实代理成功/异常计时、真实 Spring 预占链路计时、抓取零 SQL、采样失败保留旧值通过。
- 监控微基准（7 轮、每轮 100000 次调用，取中位数；预热 20000 次）：off_ns_per_call=110.71 on_ns_per_call=483.53 delta_ns=372.82 scrape_us=1757.98 scrape_db_calls=0 sample_db_calls=3。
- 即本机每次计时增量约 0.37 微秒；200 次实际 Prometheus 文本抓取使用内存快照，数据库调用为 0；一次采样固定 3 条 SQL。抓取序列化约 1.76 毫秒/次。SQL 调用数由测试 mock 验证，不是对生产 SQL 耗时的估计。

微基准使用轻量特征桩与代理，仅测量计时和抓取开销；不是端到端生产负载，不代表吞吐提升或线上开销上限。尚未执行持续压力下监控开关对照、真实 LLM 延迟实验、Grafana 实际运行验收或 Redis 灾后重建演练。下一批装配上线前需按相同库存、并发、预热、交错轮次独立进行端到端对照。

原始日志：.tmp/p0-p1-20261028/java-baseline.log、integration-baseline.log、python-baseline.log、final-verify.log。结构化汇总：verification-summary.json。

回滚顺序：先关闭观测开关并停止新增 Grafana，再回退 observability、integration、agent、inventory 单元。任何回退都需要重新运行对应测试；撤销正确性修复会重新暴露原始风险。未更改库存 Lua、订单状态机及交易数据库结构，已售库存不作任何回滚操作。
