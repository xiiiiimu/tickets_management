# 受控调度优化交付

状态：**实现与相关正确性验证完成，三个开关默认关闭；性能对照尚未执行，等待用户确认。**

分支 `codex/reservation-dispatch-controlled`，HEAD `578005dab2e453e0333c536d4224afca65489382`。原有大量未提交成果保留；本轮没有把旧未跟踪业务文件混入提交。进入本轮的现有源文件仅两份发生本轮编辑，其他源文件哈希不变，见 `results/controlled-dispatch-implementation/verification.json`。

## 实际改动

|文件|改动|
|---|---|
|`ticket-risk-platform/src/main/java/com/ticketing/ticket/ReservationDispatchScheduler.java`（新增）|默认关闭的单线程控制器；原poll-ms兜底、合并wake、有界待执行量、失败不中断后续轮询、关闭处理及固定标签指标|
|`ticket-risk-platform/src/main/java/com/ticketing/ticket/ReservationDispatch.java`|保留旧claim，新增最多4笔claimBatch；仍持协调X锁，过期租约优先，活动数一次读取，逐笔同权重/年龄选择，最后更新公平性；仅实际QUEUED事务登记afterCommit通知|
|`ticket-risk-platform/src/main/java/com/ticketing/ticket/ReservationV2Service.java`|可选独立调度；旧Spring回调在隔离时不执行SQL；可选批量供给；提交拒绝时让出全部未提交租约，一个yield失败不跳过其余|
|8份新增测试源|Batch/Scheduler/Submission/Wake四组单元契约、MySQL契约及独立JVM actor、批量事务集成和全开关异步集成|
|`performance/ControlledTestSql.java`、`controlled_dispatch_checks.py`|JDBC/RESP隔离数据与正确性runner，不调用Docker、不压测|
|设计/性能计划/规划脚本|模块边界、安全性、风险、回退和待批准矩阵|

没有修改库存Lua、Armory、状态机、画像实现、支付/出票/Agent、现有限流、业务线程池大小、容量、Hikari或数据库资源配置。新增的只有一个可选控制线程，默认不创建。入口消费者目前仍同步执行画像和裁决；最终预占事务成本仍存在，未宣称它们已优化。

## 开关

```yaml
ticketing:
  reservations-v2:
    dispatch:
      isolated: false
      batch-claim: false
      wakeup: false
```

只是说明配置键，没有改现有配置文件。wakeup=true要求isolated=true，否则启动拒绝；batch可单独开启。开关启动时读取，不提供在线切换。全关恢复原路径，无DDL/状态回迁。

## 安全性要点

- create容量检查/插入仍在协调事务内，512不变。批次新CLAIMED最多使用全局剩余活动名额；过期CLAIMED续租不重复计数，EVALUATING保留评估状态。
- 选择机会按原PASS权重/REVIEW年龄公式逐笔推进，公平性最终值与全部lease同事务提交/回滚；混合单笔/批量节点使用同一个SQL协调行。
- 同一任务新token使旧worker失效；领取提交后崩溃或未提交到sale的任务依旧可租约恢复。部分提交拒绝必须yield所有未发送项，失败项保留租约待恢复。
- 通知不参与事务成功判断；回滚/BLOCK/失去评估租约不通知，投递拒绝不把已提交QUEUED改成失败；通知可合并丢弃，fallback兜底。
- 三次Armory、Lua前可靠意图、Lua/MySQL补偿、16桶/配额/订单/Outbox一致性、202易失语义、实际预占起算600000ms均复用。

完整论证见 [CONTROLLED-DISPATCH-DESIGN.md](CONTROLLED-DISPATCH-DESIGN.md)。

## 正确性结果

|组别|执行数|失败/错误/跳过|日志|
|---|---:|---|---|
|修改前基线|9|0/0/0|`results/controlled-dispatch-implementation/baseline-tests.log`|
|候选单元|45|0/0/0|`results/controlled-dispatch-implementation/candidate-unit.log`及6份JUnit XML|
|真实MySQL|16|0/0/0|`results/controlled-correctness-mysql-20261030_011356_c948/`|
|批量预占/补偿/Armory集成|6|0/0/0|`results/controlled-correctness-locking-20261030_012231_4a04/`|
|全开关异步场景|1|0/0/0|`results/controlled-correctness-isolated-20261030_012657_4c40/`|

候选共68次相关测试执行，包含继承/跨数据库重复验证，不是声称新增68种场景。未跑全量回归和性能负载。

覆盖：并发create、实际512边界、单笔/批量混合领取、两个独立JVM竞争、全局活动上限、重复claim/请求、加权与老化、EVALUATING/CLAIMED租约恢复、旧token失效、批次中途SQL失败/外层回滚、部分sale拒绝及yield失败、通知合并/关闭/异常存活/回滚不通知；真实PASS/REVIEW预占、Lua后DB失败补偿、恢复互斥、manifest缺失、版本切换、全开关无手动dispatch及十分钟期限。

首轮组合集成runner发生测试隔离错误：两个Spring上下文共库重跑data.sql，出现测试用户主键冲突。未修改生产SQL；拆成每组独立库/Redis DB后6+1全部通过。初次失败日志 `results/controlled-correctness-locking-20261030_011827_52ef/`保留，其测试资源也已清理。

所有最终测试的cleanup.json确认自有库/账号及Redis键已清理，Docker命令0、性能运行0。数据准备只访问本机13306/26379；没有启动/停止/重建容器，没有访问真实业务数据库。

## 可复现正确性命令

在已编译本轮生产类、已有本地依赖及专属服务由用户手动运行的前提下：

```powershell
cd E:\credit_risk
python .\performance\controlled_dispatch_checks.py --suite mysql
python .\performance\controlled_dispatch_checks.py --suite locking
python .\performance\controlled_dispatch_checks.py --suite isolated
python .\performance\controlled_dispatch_plan.py
```

每个suite独立库/账号，后两组分别领取空Redis DB；不能将两组Spring上下文重新拼到一个初始化schema里。Java/Maven使用项目既有依赖，不安装生产依赖。runner仅执行相关测试并核对JUnit数量/无跳过；不是压测。

## 待批准性能计划与剩余风险

见 [CONTROLLED-DISPATCH-PERFORMANCE-PLAN.md](CONTROLLED-DISPATCH-PERFORMANCE-PLAN.md)，规划JSON由脚本生成，approved_to_execute=false。

固定A→B→B→A→A→B；相同构建，仅三个开关全关/全开；每轮新JVM、冷启动、50/10及4次重试，等价1000库存/16桶，预计10～20分钟，单测试服务RSS约350～500MiB，预留约1GiB结果空间，起始可用内存至少1GiB。一次矩阵，不按结果追加压力。以上资源是预算，不是本轮性能测量。

**执行前还须适配并验证批量感知诊断工具**：dispatchOnce方法引用不能漏记，共享batch事务/SQL不能复制成4笔后重复统计，专用控制线程不能归类成sale worker；原Docker/single-claim runner不能原样使用。此为确认后阶段的无负载预检，不会偷偷开始压力。

风险仍有：批次持协调锁更久可能拖慢create；隔离后并发IO可能增加数据库竞争；租约在慢事务中消耗，通知只能实例内加速，跨实例仍靠SQL/fallback；默认关闭尚无生产性能收益结论。入口职责缩减和最终事务/Redis IO优化保留后续候选，需要额外事件水位、失败和恢复论证。

## 回退

首选三个开关同时关闭并重启应用，恢复原Spring调度及单笔claim，现有任务/lease/意图兼容；不得删除任务或库存数据。没有数据库迁移要撤销。

若需源码回退，先核验当前文件仍是本轮候选SHA，逆向 `results/controlled-dispatch-implementation/production.patch`，仅处理上述两份既有文件和本轮新增控制类；before快照同目录。发现后续编辑则手工合并，不能整仓库reset/clean/stash或覆盖原开发成果。

本轮到此等待性能验证确认，没有云部署。
