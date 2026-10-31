# 受控整体优化设计（2026-10-30）

分支：`codex/reservation-dispatch-controlled`。保留进入本轮时的全部未提交成果，不reset/clean/stash。六份全链路诊断文档与真实代码再次核对；基线9项调度/Fence测试通过，日志 `.tmp/controlled-dispatch/baseline-tests.log`。本轮没有性能测试授权，正确性通过后只交付待确认计划。

## 模块决策

|改造|源码核对/模块|边界与共享状态|本轮决定|
|---|---|---|---|
|调度隔离|Service.dispatch在Spring共享scheduler逐笔claim，Summary.refreshPending同步做重建；没有自定义调度器|新增单线程控制调度器，只运行dispatch。原Spring scheduler仍负责summary等，入口4/sale4/Hikari10大小不变。原定时入口在隔离打开时只返回，不重复运行SQL|实施，独立开关默认false|
|有界批量claim|Dispatch.claim的协调行X锁、陈旧租约优先、活动计数、加权选择/renew|新增claimBatch(1..4)，一个RC事务只获取一次协调锁、一次stale查询、一次active计数；逐笔执行原选择公式，最后更新一次pass_run；每笔保留token/lease及记录读取|实施，独立开关默认false|
|提交后合并唤醒|decided在job锁下更新QUEUED并提交，其他实例只通过SQL看任务|只在该事务afterCommit触发本地合并通知；单线程控制器至多一个fallback定时项+一个待执行/执行中的wake任务，原25ms兜底不变|实施，独立开关默认false；wakeup要求isolated=true，否则启动拒绝|
|入口工作缩减|Ingress.Work同步执行Armory/create/evaluate/decided；评估失败留租约恢复|拆分需要定义风险执行预算、EVALUATING租约与队列满回收，否则可能只是增加第三层排队或改变恢复优先级|仅设计候选，不实现|
|事务/IO|prepare意图必须Lua前提交；reserve持有版本/风险屏障访问Redis并提交订单；冷summary同步REQUIRES_NEW|合并意图/最终事务会破坏恢复，画像并行/Redis合并涉及事件去重及窗口语义；独立guard事务已验证无整体收益|仅候选，不改现有事务/SQL/Lua/画像|

## 开关与默认行为

`ticketing.reservations-v2.dispatch.isolated=false`：默认原Spring定时线程；打开才创建一个专用控制线程（不是增加任何现有业务池大小）。

`ticketing.reservations-v2.dispatch.batch-claim=false`：默认原单笔claim循环；打开后每轮最多4个任务，批次SQL提交后才交给原sale池。两种领取方式使用同一行锁/表，混合版本可共存。

`ticketing.reservations-v2.dispatch.wakeup=false`：默认不注册同步回调；打开且isolated=true才启用afterCommit通知。禁止CallerRuns、递归执行claim或在事务未提交时发车。无通知/进程崩溃/其他实例提交由fallback接管。

均为启动读取的配置，不提供危险的在线热切。没有修改既有application配置、容量、线程池或数据库资源配置。

## 批量协议及安全性

1. 与旧claim一样在RC事务首先锁`tb_reservation_dispatch id=1 FOR UPDATE`。create仍在同锁内完成容量检查/幂等/任务插入，因此512上限不变。
2. 锁定最多本批上限的过期EVALUATING/CLAIMED，先续租，保留原状态/reservation/order/deadline，生成新token。CLAIMED恢复仍占既有全局名额；EVALUATING恢复不冒充新库存领取，批次总量仍不超过4。
3. 读取CLAIMED数量，最多按`active_limit-active`选择新QUEUED。已续租CLAIMED不能再次算新领取；实例之间仍由协调锁串行。下游完成只会减少活动数，批次保守计数不突破上限。
4. 每次选择保留原最老REVIEW/PASS SQL、权重和REVIEW年龄公式，并以当前时间重新计算年龄；本事务内更新内存pass_run，最后只持久化最终值。上一个被选任务已更新CLAIMED，下一次不会再选；并发decided按原RC语义可成为后续候选。公平性定义为领取机会顺序，不是支付/完成顺序。
5. 所有状态/token/公平性在同一事务提交或回滚。批次提交失败不提交任何sale工作。没有DDL、分布式计数器或新消息协议。
6. sale拒绝时，已成功提交的任务继续运行；**当前及所有尚未提交任务**逐个按id+token yield租约。一个yield失败仍尝试其他项；失败项保留已提交租约，15秒后恢复。不得回滚已开始的合法预占，不改变job业务状态。
7. 崩溃发生在claim提交后、submit前：租约超时仍可恢复，旧token不能穿过lockOwned。批次延迟可能缩短剩余租约，属于已有租约风险放大，最终所有权校验和意图幂等仍保证库存安全，需在后续对照中观察。

Armory三处门禁、Lua、16桶、限购、恢复意图及订单/Outbox事务完全复用；没有修改HTTP202易失收到请求语义；队列等待不开始10分钟支付期限。

## 调度生命周期与唤醒安全性

隔离后共享scheduler的dispatch入口不做SQL，独立控制器按原poll-ms执行。异常捕获并记录，不能让ScheduledFuture永久停止。wake只置合并标志并投递一个任务，不能无界堆积；执行期间新wake可被合并丢弃，fallback保证最终再查。关闭时拒绝新wake并停止自有控制线程，已领取未完成任务仍有租约。没有启动任务、自动Docker启动或外部部署。

afterCommit仅在实际EVALUATING→QUEUED更新时注册；BLOCK/过期/失去租约不唤醒，事务回滚不唤醒。回调投递失败不向已提交事务抛异常，不把已提交的QUEUED伪造为评估失败。

## 后续候选而非本轮实现

- 入口可改为只做短接纳，将风险执行交给有界资源；先证明EVALUATING容量/租约、失败重试、同user窗口与旧worker失效、风险队列拒绝不丢持久任务。不能单纯增加线程或返回预填PASS。
- 摘要后台合并与限预算可减少重复IO，但事件快照/dirty水位必须保留；不能删ready复查或陈旧默认PASS。
- Redis特征/门禁读取可评估合并，但三个门禁、缓存缺失拒绝及版本fence仍需成立。静态SKU缓存须有不可变版本写入契约。最终事务不能随意把网络检查移出锁域。

## 验证和回退

相关H2单元契约、真实MySQL并发/容量/回滚、两个独立JVM竞争、实际Redis/MySQL补偿与Armory集成；调度隔离/关闭/异常、合并通知/回滚不通知、批次部分提交拒绝必须覆盖。只连接本机已运行服务端口，创建全新测试库/账号及空Redis DB，不调用Docker、不改真实业务数据。

回退：三个开关关闭并重启服务即可恢复原调度/单笔领取，无DDL或数据回迁；处理中任务仍使用同一协议。保留旧claim供混合节点使用，恢复token/状态兼容。源码回退仅逆向本轮精确补丁，不恢复整个脏工作区。

相关正确性全通过前不进入性能验证。通过后仅交付基线/候选交错计划、资源预算和命令；须用户确认后才执行。
