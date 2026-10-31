# attempt拒绝分类、事务计时与启动保护

本轮只增加观测和启动边界，不修改容量公式、协调锁、线程数、队列、连接池、库存Lua、持久协议、数据库结构、支付出票或Agent。进入前源码保护快照：`.tmp/attempt-observability-baseline-20261030_193431`。

## 拒绝分类及兼容性

外部继续返回429、code=SYSTEM_BUSY、reliableAccepted=false、retryWithSameRequestId=true；不返回内部reason、连接池状态或SQL信息。DB_BUSY外部文案改为通用“系统繁忙”。已有可靠attempt的重试仍在新接纳/就绪检查之前返回其原记录，不产生新的登记。

新增内部counter `attempt.admission.rejected.internal{reason,detail}`：

|reason|detail|条件|
|---|---|---|
|DB_BUSY|pool_busy|原Hikari资源保护条件不满足|
|CAPACITY_REJECT|waiting_budget|原预测等待预算不足|
|CAPACITY_REJECT|hard_capacity|原512硬容量约束触发|
|CAPACITY_REJECT|user_inflight|原用户在途数量约束触发|
|CAPACITY_REJECT|alias_limit|原同attempt别名上限触发|

旧`attempt.admission.rejected{reason=system_busy}`总计数保留，便于现有仪表兼容；不要与新增内部子分类相加。暂停、入口限流、依赖异常等仍保留原有独立语义，不伪装成风险拒绝。

结构化日志事件为`attempt_admission_rejected`。每个实例每类reason最多5秒一条，所有拒绝仍逐次计数；没有新增日志线程或队列。包含queue_waiting、queue_capacity、capacity_budget、processing_global、workers_local、hikari_active、hikari_waiting、snapshot_age_ms。标签不包含userId、requestId或SKU，避免高基数。

容量拒绝使用原登记SQL已取得的数据；DB_BUSY不得再向繁忙数据库查询队列，使用最近一次快照并标注年龄，未知为-1。Hikari和本地worker槽位在拒绝发生时读取。workers_local是本地已提交、尚未结束的attempt槽位，不等于CPU正在执行的线程数；原线程池活跃Gauge仍保留。容量拒绝的日志在事务回滚返回后输出，不把同步日志IO放进协调锁临界区。

## 三个事务的计时边界

`attempt.transaction.duration{operation=register|claim|prepare,outcome=success|failure,phase=...}`：

|phase|边界|
|---|---|
|total|调用TransactionTemplate前至其提交/回滚及回调返回|
|begin|调用前至进入事务callback，含连接获取及开启事务|
|body|callback起止，含原有SQL、Redis及锁等待|
|completion|callback退出至TransactionTemplate返回，含commit/rollback、回调及外层事务恢复|

begin/body/completion按单调时钟互斥分段，单次样本和等于total；total不再与分段或原Redis/SQL计时求和。completion不是数据库服务端纯commit时间。失败的登记、领取、prepare仍分别记录failure；空claim是成功完成的事务，不代表领取成功。

显式rollbackOnly即使没有抛出Java异常，也记为failure。failure包括预期容量拒绝导致的回滚，不自动等于数据库故障，须结合内部拒绝计数区分。

`attempt.claim.granted{kind=first|recovery}`只在数据库提交后的回调增加。回滚不增加；与事务执行次数分开，避免把空轮询或重领当成新任务吞吐。

使用11个固定SLO边界的直方图，不按请求创建meter，不逐请求打印事务日志。Prometheus示例：

```promql
sum by (reason, detail) (increase(attempt_admission_rejected_internal_total[5m]))

histogram_quantile(0.95,
  sum by (le, operation) (
    rate(attempt_transaction_duration_seconds_bucket{phase="total",outcome="success"}[5m])
  )
)

sum by (kind) (rate(attempt_claim_granted_total[5m]))
```

P50/P99替换quantile即可；直方图分位为近似值。不得把begin/body/completion的P95相加成total P95。

## 生产启动保护

仅在已有`reservations-v2.enabled`和`attempts.enabled`同时开启时装配，无新增激活开关。新旧默认配置不变。

1. 只响应所属ApplicationContext的ApplicationReadyEvent，之前ready=false，不能领取。
2. 顺序检查DB SELECT 1、真实Redis PING、现有Store启动协议/结构/残留任务校验、Armory基础结构。
3. 对当前可售READY场次的待恢复上下文，调用原runtimeSku预检，预热实际路径并校验Manifest/Fence/库存结构；未就绪则不放行worker。
4. 首次检查在启动事件上执行；失败后最多每5秒由现有隔离调度控制线程重试，没有新增执行器。每个Armory预检沿用5秒技术事务，JDBC/Redis使用原有技术超时。
5. service提交worker前和Store真正claim前均有保护；新接纳在未就绪时返回兼容503。已有attempt查询/幂等重试仍可用。

该保护是启动基础设施就绪检查，不是要求所有尚未装配的场次都READY；否则空系统无法先启动再进行开售前装配。过期、下架或已切换版本的旧业务上下文仍应进入原来的拒绝/补偿路径，不能使整个实例永久卡在启动。原每请求Armory、最新风险fence、Lua版本及库存校验全部保留。

ready不是持续健康承诺；启动后依赖故障仍由原技术超时、事务回滚、租约和补偿处理。未就绪期间不延长任何WAITING deadline，也不增加PROCESSING业务截止；已有SQL截止裁决继续禁止迟到领取。保护不执行迁移、配置发布或自动补初始库存。

## 修改范围与回退

新增主类：AttemptAdmissionDiagnostics、AttemptTransactionTiming、AttemptWorkerReadiness。

修改主类：AttemptAdmissionException（允许内部受控子类，但公开code不变）、ReservationAttemptStore（分类、计时、提交后领取计数及claim保护）、ReservationV2Service（接纳与调度保护）、ReservationV2Transactions（仅prepare计时包装）。

新增测试：AttemptDiagnosticsTest、AttemptTransactionTimingTest、AttemptWorkerReadinessTest、AttemptObservabilityIT；补充ReservationAttemptStoreTest的计数/回滚/启动领取边界。专用L2入口为performance/run_attempt_observability_l2.py，仅新建归属隔离库/Redis测试数据，不迁移现有开发库。

无需数据库回滚。若撤回本轮代码，先确认文件SHA未被后续用户修改，再仅反向应用本轮补丁；不使用git reset/clean/stash，不覆盖B至F成果。保留原3处及有序Lua新增的正确性门禁，不用关闭它们回退性能。

## 下一步协调事务候选（本轮不实施）

1. 先用新指标区分DB_BUSY与waiting_budget，分清register/claim的begin、body与completion，避免错误选择优化目标。
2. 若body受协调锁等待主导，评估在同一受控协调事务中批量授予有限worker名额；必须在worker提交拒绝时归还未启动名额，并保持全局4、容量512、领取唯一及租约恢复。可靠attempt路径目前逐worker claim，不能直接套用旧持久优先级队列的batch实现。
3. 若登记body主导，评估将纯读取或幂等查询提前，但最终容量、唯一用户attempt及幂等裁决仍需在权威事务内复核。不得仅删除id=1锁或将COUNT搬出后不复核，否则可能突破容量。
4. 若begin或completion主导，先检查连接持有范围及提交/回调成本；只有新证据支持才选择下一项，不增加连接池或线程数，也不复用已证实无收益的保护行初始化移动方案。

以上候选都可能改变跨实例协调与崩溃恢复边界，应独立设计并发测试；本轮不修改协议来实现这些方案。

## 实际验证结果

原始记录：`E:\credit_risk\performance\results\attempt-observability-l2-20261030_194743`。其中L1保存编译、单元测试命令与日志；五个L2子目录保存独立库归属、完整命令、XML、SQL/Redis终态及清理记录；test-summary.json为汇总。final-source-sha256.json标识最终源码，minimal-change.patch仅包含本轮13个Java生产/测试文件，反向check通过但没有实际应用回退。

### L1

|执行组|数量|结果|实际测试进程耗时|
|---|---:|---|---:|
|初次受影响模块|60|59通过，1项新测试桩恢复写法错误|39.56秒|
|修复后就绪/状态机针对性复测|25|全部通过|40.08秒|
|最终分类/计时/回滚/并发关键边界|34|全部通过|24.78秒|
|入口兼容/就绪/风控分流回归|17|全部通过|19.72秒|

各组有重复，不将执行次数相加当成覆盖数；共79个不同相关用例最终具有通过记录。初次失败是使用when重设一个已经抛异常的Mockito桩，改为doReturn后复测，未删除断言或增加超时。

确定性时钟验证了begin/body/completion恰好覆盖total，包括提交失败、异常回滚、rollbackOnly和连接/事务开启失败。100次并发DB_BUSY与50次容量拒绝均准确计数，日志仅2条；公开HTTP错误不包含内部DB_BUSY或Hikari字段。真实H2并发覆盖5秒领取/到期竞态、全局活动上限、重复领取、回滚及旧10/30秒处理期限解耦。

受影响主类编译每次约10至12秒、测试类约9至15秒；新增L2类编译9.92秒。没有重复编译全项目或执行全部回归。L2完成后仅补正了rollbackOnly计时分类及关闭WARN时的日志计数，以上最终34项L1已覆盖，不重复无关L2。

### L2

|装配|数量|JUnit实测秒|覆盖|
|---|---:|---:|---|
|startup|2|57.110|真实Manifest缺失阻止领取、恢复后唯一跨桶订单、分段和为total、就绪后原Armory门禁仍生效|
|durable|3|76.119|超过旧10/30秒处理、5秒超时终态、Lua后SQL失败补偿|
|order|2|61.354|前序MySQL暂停不阻塞下一次Lua、结果不明时墓碑防迟到预占|
|quick|1|44.276|REVIEW缓存miss/hit、命中仍受累计限购约束|
|vector|2|60.748|跨桶分配、Armory重建、幂等释放、SQL失败回滚补偿|

10项全部通过，五组JUnit累计299.607秒，不是业务处理时间。各库结构初始化12.06至14.95秒，控制连接启动3.77至6.67秒，另外包含新JVM/Spring/Maven启动及原有fixture每场次约15秒的开售等待。没有通过扩大超时处理长耗时。

每组只初始化全新、归属明确的临时MySQL schema及空Redis DB；未向现有开发库执行V110等迁移，也未切换其持久模式。所有组schema_and_account_absent=true、redis_keys_left=0、owned_test_process_exited=true。最终只读复核无测试库/账号和Redis归属标记，无残留业务Java进程。未停止或重启现有容器。

### 未执行及风险边界

- 没有全量回归、六轮AB、吞吐压测或新的TPS/QPS结论；新增观测的性能开销尚未通过负载测量，不声称零开销。
- 没有重跑双JVM强杀或支付出票全链；这些模块本轮未改，已保留相关既有机制及测试。
- 源码校验确认inventory、sale、risk、payment、outbox、mq、workflow及全部main/resources与进入前快照一致；默认关闭的功能开关、SQL迁移文件、Lua、容量公式、连接池及线程配置均未更改。LLM/Python/Harness未修改。
- 失败关闭会暂停新接纳及恢复领取，可能使既有WAITING达到原截止；不会延长5秒或让超时任务迟到领取。多SKU恢复预检耗时随上下文数量增加，按既有512上限及各次技术超时有界；本轮没有新增后台线程绕过这一保护。
