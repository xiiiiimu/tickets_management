# 调度协调最小改造（2026-10-29）

**最终状态：候选正确性通过，但50请求组吞吐与端到端P95未达标，生产改动已精确回退。候选源码与测试归档在 `results/dispatch-optimization/`，不参与正常生产或默认测试构建。没有继续实施其他优化。**

## 源码事实与边界

`ReservationDispatch.initialize` 在启动时插入/验证 id=1 的持久调度配置。全库生产源码搜索 `tb_reservation_dispatch`：运行期只有 `create` 和 `claim` 的 `SELECT ... WHERE id=1 FOR UPDATE`，以及 claim 更新 `pass_run`。配置值必须跨实例一致，没有每实例绕过此锁的接纳或领取路径。

`create` 是 READ_COMMITTED TransactionTemplate：协调行排他锁 → request/alias 幂等 → active_owner 锁与已有订单终态检查 → 旧链路 requestId 检查 → 统计 EVALUATING/QUEUED/CLAIMED 并检查容量 → 原来的三个主体保护行 SELECT/按需 UPSERT → 插入 EVALUATING/15秒租约 → 返回行 → 提交。协调行锁保持到整个事务提交/回滚；SQL调用返回不代表释放锁。别名容量32及唯一索引保留。

`claim` 同样先锁协调行，随后优先领取过期 EVALUATING/CLAIMED 租约任务（任务行 FOR UPDATE SKIP LOCKED），否则检查 CLAIMED 数量上限，锁最老 REVIEW/PASS，按持久 pass_run、pass_weight、REVIEW 年龄选择，更新 pass_run，生成新 lease_token/15秒lease并更新 claimed_at，最后提交。租约恢复保留 reservationId/orderId/原队列期限；旧worker需通过任务状态+lease_token匹配，不能覆盖新owner。全局协调仍负责活动领取上限和公平性，任务行锁负责当前记录，二者不能盲目替代。

V106/schema.sql：协调表只有 id 主键及调度配置/公平性计数；任务表主键id，request_id、reservation_id、order_id、active_owner唯一；索引 `(state,decision,created_at)`、`(state,lease_until)`；alias以request_id为主键并索引job_id。没有DDL变更。

接纳与领取事务没有Redis/Agent调用。风险画像/规则发生在接纳提交之后；最终库存事务在另一个sale线程执行。三次Armory门禁、准备可靠意图、最终风险当前读、Lua以及MySQL16桶/订单/Outbox事务不变。

## 两个方案

| 方案 | 范围 | 正确性风险 | 回滚 |
|---|---|---|---|
| A，先独立提交保护行初始化，再获取协调锁 | 仅ReservationDispatch；增加一个REQUIRES_NEW、READ_COMMITTED模板，移一处调用 | 接纳失败留下无业务副作用的保护行；增加一次事务成本，必须实测收益；改变初始化与接纳的原子边界 | 按保存的精确补丁逆向还原单文件，无数据库回迁 |
| B，拆分原子容量计数、公平性协调及任务行claim | 调度接纳/领取/所有终态回收及恢复协议，可能新增DDL | 需证明计数回滚/重试/崩溃恢复、全局active_limit及公平性；远大于本轮范围 | 需协议兼容与可能DDL回滚 |

选择 A；不改 claim、不去掉协调锁、不改 pass_run 更新、不改线程或容量配置，不叠加其他变量。

## A 的安全性论证

保护行是风险写入与最终预占当前读的稳定锁对象，必须在最终校验前存在；不是接纳凭据、容量令牌或库存。`RiskSubjectFence.prepare` 按稳定主体顺序读/按需插入，重复初始化不改 blocked_until/version/profile，不删除行。

新顺序：独立保护行事务完整提交 → 原接纳事务原封不动执行容量/幂等/插入。其他实例不能观察到已接纳但保护行未就绪的任务。先提交后崩溃只留下保护行，不产生任务、订单或库存。初始化失败则不进入接纳事务；后续接纳失败或回滚只留下无资格含义的保护行。风险在这两段之间发生变化，由原最终 `lockAndCheck` 的共享保护锁及最新 profile/blacklist 检查重新裁决；初始化不是风险判断快照。现有BLOCK与版本不被覆盖。

容量和active_owner/requestId幂等的检查与任务插入仍由同一协调事务串行化；claim、公平性和租约状态机未变。Lua前可靠意图、最终库存/订单事务及补偿不变。没有新增Redis/MySQL一致性缺口。

当前生产唯一调用在无外层事务的 `ReservationV2Service.submit`。REQUIRES_NEW显式防止初始化意外加入外层接纳事务；测试覆盖外层回滚。未来调用者不得持有尚未提交的新保护行再同步调用create，否则独立初始化可能等待外层持锁；这不是当前路径，不能把本次改动当通用嵌套事务API。

## 验证与复现

候选 `ReservationDispatchCoordinationTest` 继承原队列契约：容量、两个调度实例并发claim、唯一领取、优先级/防饥饿、lease恢复、旧owner失效、request/alias幂等；新增并发create、并发重复requestId、保护行初始化不占协调锁、接纳回滚仅留下保护行、claim回滚恢复公平性、保护行BLOCK/version不变。`ReservationDispatchMysqlTest` 在显式创建的专属库上执行同一套契约，不连接默认业务库。两个候选测试源现归档在 `results/dispatch-optimization/candidate-tests/`；runner支持读取归档源，但会在生产源码不是已审候选哈希时安全停止，不创建资源。

如需复现实验，先确认当前生产文件仍匹配 `change-identity.json` 的 before_sha256；如有后续开发，不能覆盖。明确恢复归档的 `ReservationDispatch.candidate.java.txt` 到对应生产路径，并编译该类（或手动运行 `mvn -f ticket-risk-platform/pom.xml -DskipTests compile`），再执行以下命令。不要将归档候选当成已验收优化。原始每次运行的完整启动参数、JVM和连接配置均在对应结果目录。

```powershell
cd E:\credit_risk
python .\performance\dispatch_coordination_checks.py --suite dispatch
python .\performance\dispatch_coordination_checks.py --suite locking
.\performance\run-real-lock-diagnostic.ps1
python .\performance\analyze_real_lock.py .\performance\results\real-lock-<本轮目录>
```

两个正确性runner仅使用已运行的项目专属容器，新建独立schema/账号；库存集成测试额外原子领取空Redis DB。结束验证owner后清理自己的库、账号及键，不操作容器生命周期。locking只选四项：PASS可靠预占/幂等与十分钟期限、REVIEW无Agent、Lua后MySQL失败补偿、Armory恢复与Lua/提交屏障竞争。测试自带的JDBC参数类型规避仅作用于该集成测试，不作用于性能复测。

性能runner沿用历史四档、真实规则/16桶/Armory、原线程池队列限流、JVM与观测工具，最终终点为事务已提交的 WAITING_PAYMENT。每档独立新活动、1000库存和测试用户。CPU/GC/采样最大值/等待记录和逐请求计时见各原始目录；SQL调用耗时包含网络/锁/执行，不能直接当锁持有时间。锁等待记录是采样，不是独立事务计数或累计等待时长。单次历史对比不能证明稳定提升。

本次精确生产差异及原文件哈希：`results/dispatch-optimization/production-change.patch`、`change-identity.json`；原文件快照 `.tmp/dispatch-optimization/ReservationDispatch.before.java`。回滚前验证当前文件仍匹配候选哈希或审查后续编辑；只逆向本补丁，不使用git reset/clean/stash，不还原整仓库。回滚后重新编译部署；无数据迁移，遗留保护行仍安全。

结果见 `results/dispatch-optimization/RESULTS.md`（完成后生成）。
