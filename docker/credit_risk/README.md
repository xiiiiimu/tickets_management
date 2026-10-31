# Credit Risk 独立手动 Docker 开发环境

固定项目名：`credit-risk-dev`。本目录独立配置，默认停止；只有手动执行启动命令才启动。全部六个容器显式 `restart: "no"`，没有计划任务、开机项、Swarm 服务或自动 Compose 启动机制。

## 手动使用

先由你启动 Docker Desktop，再在 PowerShell 执行：

```powershell
cd E:\credit_risk\docker\credit_risk
.\start.ps1
.\status.ps1
.\stop.ps1
```

`start.ps1` 检查资源归属及端口，只操作固定项目；启动六个服务、等待健康并幂等初始化 `ticket-events` 主题。不会启动 Java 或 Python，也不会启动 Docker Desktop。`stop.ps1` 先调用 RocketMQ 自带 mqshutdown 平滑退出，再使用独立 Compose stop 停止本项目并保留容器、网络和数据；`status.ps1` 只读查看状态及重启策略。脚本没有自动触发。

直接停止命令（建议优先使用 stop.ps1 的平滑 RocketMQ 关闭）：

```powershell
docker compose -p credit-risk-dev -f E:\credit_risk\docker\credit_risk\compose.yaml stop
```

直接 Compose stop 也能停止，但官方 RocketMQ 4.9.7 的 shell 启动方式可能在超时后被终止，显示 Exited (137)；手动 stop.ps1 已处理这一信号转发限制。平滑停止后的 RocketMQ 显示 Exited (143)，表示按 SIGTERM 请求退出，是本次验证的预期状态。

在本目录直接运行时 Compose 自动读取本目录 `.env`；脚本始终明确传入 `--env-file`，避免读到其他项目配置。不要用 `down -v` 或删除 data 目录作为常规停机方式。

## 服务和端口

所有宿主机端口仅绑定 `127.0.0.1`，不占用其他项目的默认端口。

| 服务 | 镜像 | 容器 | 宿主机端口 → 容器端口 |
|---|---|---|---|
| MySQL | mysql:8.0.32 | credit-risk-dev-mysql | 13306 → 3306 |
| Redis | redis:7.2.0 | credit-risk-dev-redis | 16379 → 6379 |
| RocketMQ NameServer | apache/rocketmq:4.9.7 | credit-risk-dev-namesrv | 19876 → 9876 |
| RocketMQ Broker | apache/rocketmq:4.9.7 | credit-risk-dev-broker | 20911 → 20911；VIP 20909 → 20909 |
| Prometheus | prom/prometheus:v2.53.0 | credit-risk-dev-prometheus | 19090 → 9090 |
| Grafana | grafana/grafana:11.2.0 | credit-risk-dev-grafana | 13000 → 3000 |

本地 Java 使用 18082；本地 Agent 可继续用 8090。Java 和 Agent 不在 Compose 中。

网络为 `credit-risk-dev-network`。当前 Compose 将 MySQL 和 Redis 分别配置为独立命名卷：

| 服务 | 命名卷（固定名称） | 容器目录 |
|---|---|---|
| MySQL | `credit-risk-mysql-data` | `/var/lib/mysql` |
| Redis | `credit-risk-redis-data` | `/data` |

命名卷由 Docker 管理；Docker Desktop 使用 Linux/WSL2 引擎时，数据放在其 Linux 虚拟磁盘内，而不是项目 NTFS 绑定目录。实际后端、文件系统及性能仍应实测，不能仅因改了配置就宣称提交延迟改善。本次仅修改配置、**没有执行迁移或改变正在使用的挂载**。检查时使用 `docker volume inspect credit-risk-mysql-data credit-risk-redis-data`，以及 `docker inspect` 的 Mounts；不要直接访问 Docker 内部数据路径。

其余持久化仍使用本目录的 bind mount：

```
data/prometheus
data/grafana
data/rocketmq/namesrv/logs
data/rocketmq/namesrv/store
data/rocketmq/broker/logs
data/rocketmq/broker/store
```

没有挂载其他项目的数据目录、Volume、Network 或 Docker socket。镜像层可以共用，本环境的容器与数据全部新建。

旧的 `data/mysql`、`data/redis` 原样保留作回退依据，**不能删除、移动、清空或复制到新卷充当逻辑迁移**。`start.ps1` 在检测到旧绑定挂载时会拒绝自动切换，防止误启动空白开发库；`stop.ps1` 仍只停止项目、不删除卷；`status.ps1` 显示实际存储类型及卷名。

## 配置、凭据和初始化

实际随机密码保存在本目录 `.env`，已被 Git 忽略；`.env.example` 只有占位值。不要提交实际 `.env` 或 data。保留已经生成的 `.env`，更改其中初始密码不会自动修改已初始化的 MySQL/Grafana 账号。

- MySQL 数据库：`credit_risk_dev`；用户：`credit_risk_app`。
- MySQL 密码：`.env` 的 `CREDIT_RISK_MYSQL_PASSWORD`；root 密码另存 `CREDIT_RISK_MYSQL_ROOT_PASSWORD`。
- Redis 密码：`CREDIT_RISK_REDIS_PASSWORD`，默认数据库 0。
- Grafana 用户：`admin`；密码：`CREDIT_RISK_GRAFANA_ADMIN_PASSWORD`。
- Java/Agent 内部共享密钥：`CREDIT_RISK_INTERNAL_API_KEY`。

MySQL 仅在全新空命名卷第一次启动时，只读挂载并执行原项目 `ticket-risk-platform/src/main/resources/schema.sql` 与 `data.sql`。这两个 initdb 挂载保持不变；已有命名卷后续启动不会重新导入种子数据。历史 V103/V104 初始化验证见原 verification 记录，不代表本次已验证迁移。

## 手动迁移与回退（本次不执行）

迁移工具需要 PowerShell 7。先预览，不访问 Docker 或数据库：

```powershell
pwsh -File .\migrate-to-named-volumes.ps1
```

实际迁移前，先由你停止本地 Java、Python、测试客户端及其他写入者，并保持停写直到报告 `success=true`。现有 MySQL、Redis 必须仍在运行，目标两个命名卷必须不存在。执行入口：

```powershell
pwsh -File .\migrate-to-named-volumes.ps1 -Execute -WritersQuiesced
```

脚本只从本目录 `.env` 读取密码，通过进程标准输入传给容器，不把密码放在参数或输出中。`backup/<时间戳-随机号>/` 被 Git 忽略；里面有所有数据库的逻辑 dump、精确逐表计数（schema/table 名使用十六进制避免分隔符歧义）、RDB、SHA-256、逐 DB 的 DBSIZE、配置回退副本及迁移报告。dump 含业务数据和账号哈希，请按私密数据保管。

源端先核对导出前后计数，再停止服务；目标先恢复和核对 MySQL/Redis，最后启动其余服务。MySQL 使用 `--single-transaction --routines --triggers --events --all-databases`。MySQL 启动参数、flush/sync_binlog，Redis appendonly/appendfsync、两个只读 initdb 挂载及所有 restart=no 保持不变。目标卷已存在、数据漂移、计数不符、配置差异或超时都会停止，不自动覆盖或重试。切换前失败保留原运行环境；切换后失败停止本项目服务，保留所有备份和新卷。

Redis 7 开启 AOF 后优先装载 AOF，直接替换 `dump.rdb` 不足以恢复。工具只在全新卷中保留初次启动的 AOF 目录，再用备份 RDB 构成 multipart AOF 的 BASE/manifest；校验文件、DBSIZE、执行 BGSAVE/BGREWRITEAOF，并再次停启 Redis 核对。此过程不临时关闭 appendonly，不改 appendfsync。[Redis 官方加载代码](https://github.com/redis/redis/blob/7.2/src/server.c)、[AOF manifest 格式](https://github.com/redis/redis/blob/7.2/src/aof.c)。

注意：TTL 在迁移期间仍按原期限流逝；过期导致 DBSIZE 不相等也会停止，不会延长 TTL 或假报成功。精确表计数包括 mysql 系统表，系统统计表发生变化也会停止并记录差异。脚本不会修改事件调度、日志、Redis 数据库数量或持久化配置来通过校验；不兼容时须另行核对。

回退需先停止本项目，再把备份的 `compose.bind.rollback.yaml` **复制回本目录的 compose.yaml**，然后使用该文件的 scoped Compose up。不能直接以 backup 内文件为 `-f` 启动（相对绑定路径会变化）。旧 `./data/` 完整保留，新卷也不删除；回退不会包含迁移后产生的新写入，开始恢复业务写入前应决定是否接受新环境。

`verification/` 中的 JSON 和状态日志是 2026-10-28 的历史证据，未改写。两个 Python 验证器已适配命名卷，但仍保留当时的种子数据、容器 ID/server_uuid、原资源基线断言；因此不是迁移后的通用验收器，不应直接在已有开发数据上运行。迁移使用本脚本独立生成的计数和报告验收。

本次初始验证：2 个用户、1 个演唱会、3 个 SKU、0 个订单；四张 V103/V104 表齐全。未复制旧数据库，未连接、迁移或清理其他项目数据库。

Grafana provisioning 和看板直接只读挂载本项目原有 `docker/grafana` 文件，不重复维护业务看板。环境专用 Prometheus 目标和 Broker 地址放在本目录模板中，启动时生成：

- `prometheus/prometheus.yml.template` → `prometheus/prometheus.yml`
- `rocketmq/broker.conf.template` → `runtime/broker.conf`

修改本环境端口请修改 `.env` 后手动重启。Broker 对本地 Java 公告 `127.0.0.1:20911`，宿主机与容器 RPC 端口保持一致，VIP 端口始终等于 RPC 端口减二；不能只改宿主机映射而保留旧公告地址。

## 访问地址

- Prometheus：[http://localhost:19090](http://localhost:19090)
- Grafana：[http://localhost:13000](http://localhost:13000)
- Java 健康检查：Java 手动启动后访问 [http://localhost:18082/actuator/health](http://localhost:18082/actuator/health)
- Java Micrometer：`http://localhost:18082/actuator/prometheus`

Prometheus 从容器内采集 `host.docker.internal:18082`，任务名 `credit-risk-java`。Grafana 的原有 `ticket-prometheus` 数据源连接新网络中的 `http://prometheus:9090`。Java 停止时其 target 为 DOWN 是正常情况；容器停止后这些网址不提供服务。

## IDEA 连接本环境

新建独立 Run Configuration，保留原配置，不修改 Java 业务源码或原 `application.yml`。

1. Main class：`com.ticketing.TicketPlatformApplication`。
2. Program arguments：

```text
--spring.config.additional-location=file:E:/credit_risk/docker/credit_risk/application-dev.yml
```

3. VM options：

```text
-Dspring.jdbc.getParameterType.ignore=true -Duser.timezone=UTC
```

旧 Connector/J 与 MySQL 8 空参数元数据探测的兼容设置只在这个运行配置中使用。数据库及验证使用 UTC。

4. 在这个 Run Configuration 的环境变量中导入本目录 `.env`；若 IDEA 无文件导入功能，至少填入下列三个必需的私密值，其余端口/用户名使用本文件列出的默认值：

```text
CREDIT_RISK_MYSQL_PASSWORD=<从本目录 .env 读取>
CREDIT_RISK_REDIS_PASSWORD=<从本目录 .env 读取>
CREDIT_RISK_INTERNAL_API_KEY=<从本目录 .env 读取>
```

如果修改过 `.env` 的端口、数据库名或用户名，应一并传入对应 CREDIT_RISK_* 变量。不要继承其他项目的 `SPRING_DATASOURCE_*`、`SPRING_REDIS_*`、`ROCKETMQ_NAME_SERVER`、旧 `--server.port` 或数据库启动参数，以免覆盖独立配置。

新附加配置得到的连接为：

```text
JDBC: jdbc:mysql://127.0.0.1:13306/credit_risk_dev?useSSL=false&serverTimezone=UTC&useLegacyDatetimeCode=false&characterEncoding=utf8&allowPublicKeyRetrieval=true
User: credit_risk_app
Redis: 127.0.0.1:16379，密码见 .env
RocketMQ: 127.0.0.1:19876
HTTP: 18082
TicketSaleArmory: enabled=true
Inventory buckets: 16
```

这里只统一启用功能与桶配置，不会自动把种子票档切成库存桶，也不会把已有交易在线迁移为装配模式。需要首次纳管的新场次必须按已有开售装配流程准备。

Python Agent 仍由你在本地手动运行；其 `INTERNAL_API_KEY` 应取 `.env` 中 CREDIT_RISK_INTERNAL_API_KEY 的同一值。未配置真实 LLM 或未运行 Agent 时沿用已有降级，不在这里新增外部调用或 API Key。

## 已执行验证与持久化

`verification/services-checks.json` 记录 MySQL 版本/数据库身份、初始化表、Redis PING、RocketMQ 路由、Grafana 数据源、容器挂载与重启策略。`java-metrics-check.json` 记录实际临时本地 Java 为 UP、Prometheus up=1 及真实 JVM 样本；该验证 Java 已结束，没有留下额外 Java 服务。

验证使用项目已构建 jar，不修改源码；Java 探针关闭业务定时任务，仅检查依赖和指标。`verification/java-probe.ps1` 是仅本次手动执行的本地验证助手，不会被启动脚本或任何系统任务调用。

Redis 中保留一个独立的 `credit-risk-dev:verification:persistence` 标记，仅用于手动重启后验证 AOF，不占用业务库存/风险 Key。手动停止再启动后检查数据库 server_uuid、Redis 标记以及容器 ID 保持不变，最后再次停止全部新容器。最终状态与资源隔离检查见 `verification/final-report.json`。

## TicketSaleArmory A/B 压测准备

本次没有运行压力测试，也不为性能对照清空开发数据库。

1. 手动启动本环境，确认 6 个服务健康；IDEA 使用上述独立运行配置。所有参与进程必须连接同一新数据库、Redis、NameServer，统一桶数量和时区。
2. 使用全新专用 A/B 场次、SKU、用户和请求编号，开售时间留出准备窗口。不得把已交易票档在线切换库存拓扑，不得为重复压测重置已售库存。
3. A/B 保持相同真实桶数、总库存、票价、限购、规则、请求数量、并发、JVM/连接池和消息配置。A 走未纳管的原路径，B 在开售前调用装配端点发布 READY；两者都必须校验同样的库存不变量。A 的新桶需由专用数据准备步骤初始化，不混淆为单行库存对 16 桶的另一项实验。
4. 明确 PASS/REVIEW 比例。若测 Agent 影响，使用同样的固定延迟测试服务与返回值；不混入真实模型延迟或把拒绝请求算作成功抢票吞吐。
5. 充分预热，交错执行多轮；采集成功下单/出票吞吐、P50/P95/P99、连接池等待、sale_admission / sale_intent_gate / sale_reserve_gate、SQL 库存耗时、MQ 完成时间和补偿。
6. 每轮确认 16 桶合计、Redis 余额、未释放分配、订单/出票、用户限购及重复请求一致；待异步任务完成后再进行下一组。性能收益必须由数据证明。

**不要直接运行旧 performance/compare_inventory_buckets.py 或 compare_post_issue_risk.py。** 它们固定使用 `credit-risk-ab-*` 容器及旧凭据，HTTP 也使用 18082；需先另行适配为本环境参数，并增加 `credit-risk-dev` 资源归属校验。本次没有执行或修改这些旧脚本。

## 不自动启动的约束

全部实际容器 RestartPolicy.Name 必须为 no；无 `always`、`unless-stopped`、`on-failure`，没有 Docker Swarm/Windows 任务/启动项。脚本必须由你主动调用。未重启 Docker Engine 或修改 Docker Desktop 全局设置来做验证，以免影响其他项目；最终所有新容器保持 Exited，网络和数据保留。

原有 Docker 资源只做了只读基线对照，没有停止、重建、删除、修改或挂载其数据。没有 git reset、git clean、stash，没有覆盖现有业务配置。

## 历史首次创建验收状态（不代表命名卷迁移）

验收时间：2026-10-28 10:49:44 +08:00。

- 六个容器全部 Exited，实际 HostConfig.RestartPolicy.Name 全部为 no。
- MySQL、Redis、Prometheus、Grafana 退出码 0；RocketMQ 两个进程按 SIGTERM 停止，退出码 143。
- 一个新网络 credit-risk-dev-network；新建命名 Volume 0、匿名 Volume 0，数据保留在本目录。
- 手动停启后 MySQL server_uuid、Redis AOF 标记和六个容器 ID 保持不变。
- 临时本地 Java 健康 UP，Prometheus 实际 up=1，获取 8 条 JVM 内存指标；临时进程已停止。
- 原有 8 个容器的状态、启动/停止时间、挂载及策略不变；原网络和 Volume 不变。
- 214 个既有业务/配置文件哈希以及原 Git 索引不变。真实密码未泄露到 .env 以外的交付文件，.env 和 data 已确认被 Git 忽略。
- 没有匹配本环境的计划任务。复核中出现的一次再次启动，用户已确认是手动操作；之后再次只停止了本环境。

完整配置文件清单见 verification/created-files.txt，状态证据见 verification/final-report.json、docker-ps-final.txt 和 docker-ps-a-final.txt。
