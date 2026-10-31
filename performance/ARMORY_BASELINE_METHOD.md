# TicketSaleArmory 性能基线：操作与口径

本轮只增加测试工具、测试资源和报告。没有修改 Java 业务代码、Lua、订单状态机或开发库数据。正式结论及本次原始日志见 `ARMORY_BASELINE_REPORT.md`。

## 环境与安全边界

- MySQL / Redis 使用 `credit-risk-dev-mysql`、`credit-risk-dev-redis`。每轮创建 `credit_risk_bench_<时间>_<轮次>` 数据库，独立生成的数据库账号只授权这些测试库；密码留在已忽略的结果目录 `.env`。
- Redis DB0 留给开发环境。脚本只使用指定的空逻辑库，先检查 `DBSIZE == 0`，再写所有者标记；不执行 FLUSHDB、DROP、DELETE 清理。
- E 盘使用率 96%，原开发 Broker 触发磁盘保护。正式实验使用单独的 RocketMQ 4.9.7 NameServer / Broker：21976、21911、21909。全新 `credit-risk-bench-mq-store` 数据卷位于 Docker Linux 文件系统，保持 SYNC_FLUSH，所有容器 `restart: "no"`。
- 本地 Java 端口 18083，运行前检查空闲，只停止脚本自己启动的 PID。固定 JDK 21、128–384 MB 堆、SerialGC、Hikari 10；不同时启动两个测试 Java。
- 数据库使用现有 schema.sql + data.sql，已包括 V103/V104 结构。每轮独立的 warm / measured 场次各有一个 SKU，各 1,000 张、16 桶。每桶 0–7 为 63 张，8–15 为 62 张。
- 所有 11 条风控特征规则启用；仅测试库提高共享 IP 的 IP_FREQUENCY、IP_ACCOUNTS 阈值及 DECISION_POLICY.rateLimit 至 100000。老账号、独立用户/设备/requestId、单张 10 元订单得到 PASS；不调用 Agent。这是测量 PASS 同步链路的固定夹具，不是生产风控建议。
- A 关闭 Armory，使用已有桶结构和配置发布入口；B 开启 Armory，通过管理 API 在未来开售前装配 READY。两组都使用原库存、限购、幂等及履约实现。

## 复现命令

先确认没有其他 Java 消费本项目的消息、18083 / 21976 / 21911 / 21909 空闲、有充足磁盘空间。不要停止其他项目以让出端口。

```powershell
cd E:\credit_risk
.\docker\credit_risk\start.ps1
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.11'
mvn -f ticket-risk-platform/pom.xml package '-DskipTests' '-Dstyle.color=never'

# 第一次创建测试 MQ 前，确认以下名字不存在，避免复用未知数据。
docker ps -a --filter name=credit-risk-bench-
docker volume ls --filter name=credit-risk-bench-mq-store
docker compose -p credit-risk-bench-mq -f performance/armory-mq.compose.yaml up -d
docker exec credit-risk-bench-broker sh -c 'cd /home/rocketmq/rocketmq-4.9.7/bin; sh mqadmin updateTopic -n namesrv:9876 -c credit-risk-bench-cluster -t ticket-events -r 8 -w 8'

python performance/armory_baseline.py
python performance/analyze_armory_baseline.py performance/results/armory-<本次时间>
```

脚本会拒绝已占用的 Redis 逻辑库。本次主实验和深诊断占用 DB2–DB9，无重采样对照占 DB10–DB11，失败预检占用 DB1；这些测试数据保留。后续重跑应准备新的独立 Redis 数据实例并调整脚本的容器、端口和逻辑库映射，或先明确批准仅清理已确认归属本次测试的逻辑库；不要对开发库 DB0 或未知数据执行清理。测试 MQ 数据卷保留后也必须确认无其他消费者、无遗留消息，再复用；以上命令并非无条件重复执行的清理脚本。

运行结束后恢复手动停止状态，保留数据：

```powershell
docker compose -p credit-risk-bench-mq -f E:\credit_risk\performance\armory-mq.compose.yaml stop
E:\credit_risk\docker\credit_risk\stop.ps1
docker ps
docker ps -a --filter name=credit-risk-
```

停止即为运行环境回滚；只新增的 Compose、broker 配置及两个 Python 工具可单独撤回。无需回滚生产代码。数据卷、测试库和逻辑库不自动删除。

## 测量口径与局限

主对照顺序 A1 → B1 → B2 → A2 → A3 → B3，每轮新 Java、新数据库、新 Redis 逻辑库，三批各 160 请求预热，预热订单逐批出票排空。另对 measured SKU 做 1 笔目录预热；正式 32 工作线程、160 请求，初始可用量 999，最终 839，不会耗尽。各轮没有重复 requestId，也不重复使用用户和设备。

QPS 为 160 / 客户端批次墙钟时间，不包含出票排空；延迟从工作线程实际发 HTTP 开始，包含网络及服务处理，不包含执行器排队。P50 / P95 / P99 使用 nearest-rank；总表延迟合并每组 480 请求，QPS 为各组三轮中位数。160 请求只能用于小批量回归，不能外推持续容量或稳定尾延迟；固定三批预热也不等于已证明达到 JIT 稳态。

`RequestTimings` 提供请求 ID 可关联的精确阶段分布。`BusinessMetricsAspect` 的 count / sum 差值提供方法调用数量和均值；其中 sale_admission 包含短事务、sale_intent_gate / sale_reserve_gate 在所属事务内。嵌套阶段不可相加；固定 histogram 桶只能给分位范围，不能当成方法的精确 P95。

`intents.create`、`transactions.reserve` 包含连接获取、事务体和提交。`.tx_begin` 包含连接获取及事务初始化，不能全部解释成 Hikari 等待；Hikari acquire 的 count / sum 才是连接获取的总体观测，但包含同时执行的后台任务，不能直接当成每个 HTTP 的等待。`redis_reserve` / `redis.operation.latency` 是 Java 到 Redis 执行 Lua 的客户端包围耗时，包含调度、网络和反序列化，不等同于 Redis 服务端脚本 CPU 时间。

Hikari MXBean 每 200ms 采样，不访问数据库；采样任务与其他定时任务共享调度线程，报告必须检查实际样本间隙，不能将样本均值误当成时间加权均值。请求窗口只抓前后两次全量 Micrometer，不在热路径循环抓取。

performance_schema statement digest 差值为 SQL 服务端累计耗时，覆盖同步和同时执行的后台 SQL；与客户端 JDBC/连接池等待不是同一口径。SQL 的 SUM_LOCK_TIME 不能直接解释为 InnoDB 行锁时间。全局 Innodb_row_lock_* 包含该 MySQL 实例其他事务，不能只归因于 assembly；额外诊断组采样 data_lock_waits，只有实际表名/索引才能帮助定位。

主对照保留支付、出票、MQ、Outbox、补偿和缓存刷新，另保存 HTTP 请求结束至全部出票的 SQL 尾部。A-deferred / B-deferred 仅使用已有 jobs / mq 开关暂停执行，仍正常写订单和 Outbox，保留快照定时采样；其预热订单为 WAITING_PAYMENT，数据历史及资源负载不同，且启用 JFR 和锁采样。因此只用于资源争用诊断，不并入主对照，也不是生产优化结果。诊断结束后这些测试订单留在专用测试库，不自动重启履约。

当前 Prometheus 配置采集开发 Java 18082，Grafana 使用 ticket-prometheus 数据源。本次 18083 直接保存 Micrometer 原始文本；没有将开发 Dashboard 的历史曲线冒充本轮结果，也没有改写开发监控配置。

补充对照复现：python performance/armory_baseline.py --resume performance/results/armory-<本次时间> --quiet-controls。resume 只允许跳过已完成轮，或继续同一运行中零订单的准备阶段。
