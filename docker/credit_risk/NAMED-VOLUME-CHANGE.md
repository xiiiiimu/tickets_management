# 命名卷与并发提交工具变更（2026-10-30）

本次仅修改配置/脚本并做离线校验。没有执行迁移脚本（含 dry run），没有连接数据库，没有启动、停止、重建容器，没有读写旧数据文件。命名卷尚未由本次操作创建，实际后端文件系统和迁移后延迟尚未测量。

## 数据目录引用清单

对 `docker/credit_risk/` 和 `performance/` 搜索了 `data/mysql`、`data/redis` 以及单/多反斜杠转义形式；实际数据目录和私密备份不作为源码扫描对象。结果与语义审计如下。精确行号及 SHA-256 见交付的 references.json / sha256.json。

| 文件/位置 | 原用途 | 处理 |
|---|---|---|
| compose.yaml mysql volumes | `./data/mysql` 绑定 | 改为 credit-risk-mysql-data；固定同名卷，保留原两个 initdb ro 挂载 |
| compose.yaml redis volumes | `./data/redis` 绑定 | 改为 credit-risk-redis-data；appendonly/appendfsync 命令不变 |
| _common.ps1 / Assert-CreditOwnership | 原来只检查容器/网络 | 增加 docker volume inspect 项目归属检查；没有直接读写旧数据目录的代码 |
| start.ps1 | 原来无挂载迁移保护 | 先检查实际 Mounts；新 Compose 对应旧 bind 时拒绝自动切换，不会悄悄创建空白库 |
| stop.ps1 | 仅 scoped Compose stop | 无数据目录操作，无须修改；文件哈希不变，仍保留容器及卷 |
| status.ps1 | 仅容器状态 | 增加实际 Mounts 类型、目的路径和卷名输出；不访问宿主机数据库文件 |
| README.md 持久化清单/初始化说明 | 声明全部 bind mount、空 data/mysql 首次初始化 | 改成命名卷，旧路径只用于回退说明；保留并明确标记首次创建历史证据 |
| verification/created-files.txt | 持久化目录清单 | 改为两卷 + 其他 bind；旧 MySQL/Redis 目录标为保留的回退目录 |
| verification/final-audit.py | auto.cnf 忽略检查、断言没有新卷 | 改用卷 inspect/Mounts 及 data/backup 目录忽略检查，预期两个命名卷；不读取 auto.cnf |
| verification/check-services.py | 语义上假定所有可写挂载为 bind、没有卷 | MySQL/Redis 改为固定卷名/目的路径/项目标签校验，其他服务仍校验原 bind 路径 |
| verification/services-checks.json | 首次验证实际 Mounts 包含旧路径 | 历史证据，**保留原文**，不伪造新挂载证据 |
| performance/ 下原有脚本、结果和日志 | 未找到指向这两个宿主机数据目录的字面路径引用 | 无须适配数据文件访问；mysql_commit_latency.py 仅改独立 schema 并发模式 |
| migrate-to-named-volumes.ps1（新增） | 验证旧 bind 归属、生成回退 Compose | 旧路径仅参与 Mounts 来源校验/回退文本；备份通过 docker exec/cp，恢复只写全新命名卷 |

`org.springframework.data.redis` 是 Java 包名，不是数据目录引用，未混入清单。

## 待确认 / 不能由本次静态校验证明

1. `verification/check-services.py` 与 `final-audit.py` 仍有首次创建的种子数量、容器 ID/server_uuid 和旧全局资源基线断言。它们不是现有开发库迁移验收器，不能为适配新卷而悄悄重写历史 JSON。迁移后若要继续使用这些历史检查，须另行建立新的实测基线。
2. 没有检查当前容器状态、当前数据量、Redis DB 数量、TTL 或 Docker 后端。本次不得连接数据库；迁移执行时严格检查，存在不一致就停止。
3. 精确行数包含 MySQL 系统表，其动态统计可能变化；Redis TTL 也会正常流逝。脚本发现变化会报告差异，不会跳过、修改数据或放宽一致性断言。
4. 脚本尚未做真实恢复演练。本次只允许解析/编译；不能把静态校验称为迁移成功或性能改善。

## 迁移协议

默认只打印步骤，只有 `-Execute -WritersQuiesced` 才操作资源；要求操作者已停写，脚本不擅自停止 Java/Python。先验证项目归属和两个目标卷均不存在，再进行所有数据库 mysqldump、前后精确行数、Redis BGSAVE/RDB 和逐 DB DBSIZE 备份。备份 SHA-256 和恢复报告保存在被 Git 忽略的 backup/。

调用 stop.ps1 后，用带本轮唯一标签的新卷启动依赖服务。导入 MySQL 并逐表核对；Redis 保持 AOF 开启，以 RDB BASE + manifest 恢复，执行文件校验、DBSIZE 和 AOF 重写后再次停启验证。其余服务仅在数据核对通过后启动。失败不自动重试/回退；切换后失败停止项目服务，保留新卷与备份。

保留旧绑定目录，绝不删除/移动/清空。回退时停止项目，将备份 compose.bind.rollback.yaml 复制回原 Compose 目录再启动；相对路径不可从 backup/ 直接运行。回退不包含迁移后新增的写入。

## 提交延迟并发模式

```powershell
# 本次仅执行此离线编译方式；不会连接数据库。
python performance/mysql_commit_latency.py --connections 64 --compile-only
```

实际运行仍需要显式 `--execute`、环境密码和全新 UUID schema。`--connections` 默认 1，范围 1–64；每个连接更新自己的 id，先共同起跑预热 3 次，再经测量屏障开始各自的 samples 次提交。结果包含每连接分布、合并分布、总提交数、首个测量 UPDATE 开始至最后 COMMIT 完成的墙钟时长及 TPS。预热、建库、建连接和日志输出不计入测量窗口。

单连接的 SAMPLE 行、samples.csv 三列表头以及 result.json 的 commit_ms / update_and_commit_ms 保留；多连接 CSV 增加 connection 列。任何连接失败都使整次失败，不把部分成功样本当作完整吞吐。原 localhost、技术超时、归属标记和校验后清理约束保留。
