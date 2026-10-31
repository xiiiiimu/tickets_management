# 阶段B：可靠attempt与5秒领取边界

本轮只实现B：可靠登记、WAITING截止、原子领取/超时、PROCESSING期限解耦和必要背压。不实现C的有序Lua、D的快复核缓存或E的跨桶。没有修改画像生成、LLM/Python Agent、Harness、支付/出票业务语义或现有Lua。

分支：`codex/attempt-lifecycle-phase-b`。默认开关关闭，未部署、未切换现有开发库；所有真实测试使用新建owner校验的schema和空Redis DB。所有原有未提交成果保留；阶段A保存的源文件基线用于区分本次改动。

## 一、实际调用链与202边界

```
Controller @Valid/身份一致性
  → ReservationIngress 现有限流/关闭检查
  → registerAttempt 指纹和幂等查询、资源检查、Armory runtimePlan
  → ReservationAttemptStore.register [REQUIRES_NEW / READ_COMMITTED / 3秒技术事务超时]
      协调容量锁 → 幂等/active_owner/用户上限 → 有界容量判断
      → 数据库时间 → INSERT WAITING + waitingDeadline + 稳定关联ID
      → COMMIT
  → 用已提交登记快照返回202（不再获取job行锁等待库存）

独立控制线程：到期清扫/租约续期/最多4条非持有式可领取提示
  → 已有sale池成功启动worker
  → worker内原子claim，提交WAITING→PROCESSING
  → 现有资格算法（阶段D才替换）→ 持久裁决，继续PROCESSING
  → 原Armory intent gate + 可靠意图
  → 原Armory reserve gate/风险限制fence/Lua/16桶/订单/Outbox事务
  → RESERVED/WAITING_PAYMENT，或幂等失败补偿
```

POST仍在现有`/api/tickets/reservations`；202新增attemptId=job.id、requestId、权威state、waitingDeadline、reliableAccepted=true及terminal/pollingSuggested。reservationId是关联ID，只有reservationSuccess=true且订单提交才表示成功。

登记失败/提交结果未知不返回202；503/VALIDATION_UNAVAILABLE提示同requestId查询/重试，不能据此断言数据库一定没写入。容量/依赖资源拒绝为429/SYSTEM_BUSY；原限流拒绝为RATE_LIMITED，不伪造QUEUE_TIMEOUT或SOLD_OUT。

相同requestId和指纹返回同一已存结果；同一有效user/session的重复请求复用原job并保留别名。终态原ID永远返回原终态；前端明确点击“本次已结束，开始新请求”才生成新ID。

GET by-request仍可跨实例查持久结果；新增`/api/tickets/reservations/attempts/{attemptId}`。不存在/数据库不可用不自动判抢票失败。首次202是登记提交时的快照，worker可能在响应抵达前已经推进，后续查询提供新状态。

## 二、5秒截止及PROCESSING

- `waiting_deadline=acceptedAt+5000ms`。acceptedAt取登记事务最后插入前的数据库时间，不以客户端时钟或HTTP抵达后的时间补5秒。事务提交/网络时间会占用这段预算，不能承诺客户端收到202后还有完整5秒。
- WAITING无lease、无库存副作用。worker先实际占到sale执行槽才执行领取；池拒绝不改状态，禁止CallerRuns。
- 领取在现有容量协调事务内锁候选；锁定后重新取数据库时间，`deadline>now`才可条件更新PROCESSING。`deadline<=now`只允许QUEUE_TIMEOUT。等于截止归超时，不用锁等待前的过时时间。
- 到期清扫与领取均受job行锁及state条件保护，QUEUE_TIMEOUT不在领取/恢复查询中，active_owner清空。查询也可在短事务内物化到期终态，避免依赖清扫器绝对准时。
- 独立控制线程复用现有ReservationDispatchScheduler，不扩大业务池。新模式必须显式启用dispatch.isolated，避免摘要刷新共享scheduling-1造成长时间清扫延迟。JVM停顿/数据库故障仍可能延后终态物化，但不能授权截止后的领取；这不是硬实时系统。
- PROCESSING仅使用15秒技术租约；每5秒尝试续期，失联可重新领取，轮换token但不重置attempt、processing_started_at、waiting_deadline、reservation/order ID。
- 最终事务凭状态+token持有job锁；旧worker不能覆盖新owner/终态。新PROCESSING不再使用旧queue_expires_at终止资格、意图或预占。
- 不修改库存Lua：新PROCESSING传给原Lua的ARGV[8]是已通过Armory验证的saleEnd，不是排队截止，也不是另设10秒/30秒截止。销售结束仍可拒绝，结果区分为SALE_WINDOW_CLOSED。旧协议继续原有QUEUE_EXPIRED语义。
- Redis仍用既有2秒技术超时；新模式要求JDBC URL显式有正connectTimeout/socketTimeout且各不超过30秒，不自动改现有连接配置。元数据事务3秒，最终新协议事务5秒技术超时，原协议事务配置不变。处理可以跨多个技术调用累计超过30秒，单个外部调用/租约仍有边界。

## 三、入场与兼容

硬容量继续来自持久设置512，PROCESSING/CLAIMED合计活动上限继续4；不增加sale线程、原内存队列容量、限流或连接池大小。新协议不再把原4/256内存队列作为可靠队列使用，它仍供旧协议兼容。

接纳还受保守预测约束：默认单任务初估2秒，随后按实际worker耗时更新EMA；使用4.75秒预算及空闲槽估计可开始的数量。每用户默认最多4个WAITING/PROCESSING，幂等重试不占新名额。当前冷启动预测通常最多12个待领取，不把512当允许积压5秒的目标。

Hikari等待/满载会预拒绝；Armory的Redis读取失败/超时不返回可靠202。预测只是本地保守预拒绝，权威硬容量由数据库控制；它不是多实例5秒履约保证。尚未在持续负载中校准全局claim吞吐、Redis/MySQL负载模型，不能据此承诺容量。

新模式仍用现有id=1协调行保护容量与活动上限，不用它实现资格顺序。该热点尚未消除。本阶段为确保“worker已实际开始才领取”，新WAITING协议逐笔claim；旧claimBatch完整保留并通过回归，未把它误当200ms屏障删除。阶段C需要结合预注册执行槽和SKU顺序再评估安全批量供给，不宣称此处吞吐已提高。

旧`/api/tickets/reserve`不能接管协议3的canonical request或alias，包括已经存在REQUESTED意图的情况。旧协议自己的请求重试保留。启动拒绝混用runtime_protocol；也拒绝遗留旧EVALUATING/QUEUED/CLAIMED或旧孤立REQUESTED意图，不自动清理/改写它们。

## 四、实际修改文件

|文件（ticket-risk-platform下）|作用|
|---|---|
|ticket/ReservationAttemptStore.java（新增）|可靠登记、数据库时钟、幂等、容量、原子领取/超时、租约和持久裁决|
|ticket/AttemptAdmissionBudget.java（新增）|有界预测及Hikari资源预拒绝；不在监控抓取时查询数据库|
|ticket/AttemptAdmissionException.java（新增）|明确SYSTEM_BUSY/VALIDATION_UNAVAILABLE等拒绝语义|
|ticket/ReservationV2Service.java|可靠HTTP登记入口、worker实际领取、心跳及PROCESSING处理路径；旧分支保留|
|ticket/ReservationIngress.java|新模式不走易失队列；保留原限流和状态查询保护|
|ticket/ReservationDispatch.java|启动协议一致性、可靠状态视图、阻止新模式误走旧create；旧claim/claimBatch不重写|
|ticket/ReservationDispatchScheduler.java|提交后唤醒的说明泛化为持久任务；既有独立控制线程复用|
|ticket/ReservationV2Controller.java|attemptId查询、结构化接纳异常响应|
|ticket/ReservationModeController.java|公布DURABLE_ATTEMPT语义及5000ms等待规则|
|ticket/TicketController.java|阻止旧入口绕过协议3租约/状态机|
|ticket/ReservationV2Transactions.java|PROCESSING状态/token、旧期限解耦、5秒技术事务、保护行延迟到意图事务；Lua原样复用|
|ticket/ReservationRuntimeRecovery.java|活跃PROCESSING租约不被旧恢复器误补偿|
|resources/db/migration/V110__durable_attempt_lifecycle.sql|4个附加字段、3个索引；不迁移库存/订单|
|resources/schema.sql|新建库的完整结构同步|
|resources/application-reservations-v2.yml|默认关闭的新开关及冷启动估值，未改变池大小|
|test/.../ReservationAttemptStoreTest.java|状态/时钟/竞态/幂等/容量/事务回滚|
|test/.../ReservationAttemptEntryTest.java|202边界、提交拒绝、旧入口保护、空队列不空转|
|test/.../ReservationAttemptTransactionTest.java|超过旧10/30秒仍确认、旧token/终态不碰库存、旧协议不回归|
|test/.../ReservationAttemptMysqlTest.java|真实迁移保留旧行、InnoDB锁/截止/双实例/恢复|
|test/.../integration/DurableAttemptLifecycleIT.java|真实Redis鉴权+Controller、Armory、Lua/MySQL确认及补偿|

另修改ticket-risk-web/src/App.tsx：分别显示可靠排队/处理中，5秒仅限制排队；120秒仅是客户端自动查询预算，停止查询不会取消PROCESSING或认定失败。可靠模式查询不携带客户端receivedAt作为服务端截止依据，保存服务端登记时间，客户端查询预算单独按本地时间计算，避免时钟偏差干扰可靠状态查询。明确终态后才清除旧ID并新点击。未改其他页面业务。

## 五、迁移与回退（均未在现有开发库执行）

V110仅新增：dispatch.runtime_protocol默认2；job.attempt_protocol默认2、waiting_deadline可空、processing_started_at可空；waiting/lease/user索引。旧行原字段不改，旧订单/库存不重算。MySQL测试真实执行V106→V110并逐字段检查旧fixture保持不变。

新建库用更新后的schema.sql；已有库只应用一次V110，不要在新建完整结构后再重复应用。DDL隐式提交，若部分失败应检查元数据，不得盲目重复执行或删除数据。

启用前需全实例兼容构建、旧未完成任务/意图排空核验、审核技术超时URL，再单独审核修改持久runtime_protocol为3。代码不会自动把已有设置从2切到3。仅在本轮新建测试库中显式设置了3。

配置示意（仅说明，不是已部署配置）：

```yaml
ticketing.reservations-v2.attempts.enabled: true
ticketing.reservations-v2.dispatch.isolated: true
# JDBC URL同时明确connectTimeout/socketTimeout；既有Redis timeout=2s保留。
```

**阶段B不是最终生产资格策略**：仍复用原风险调用，未实现用户确认的新画像回源/快速REVIEW语义；C–E完成前不应将该开关用于正式放行。

回退：停止接纳→等待或安全终结新WAITING/PROCESSING及未完成意图→核对所有库存/订单/补偿→审核将runtime_protocol切回2、关闭新开关并恢复兼容代码。B仍使用原库存marker协议2，已提交订单支付/释放仍由原链路处理。附加列/索引可以保留，不需要DROP；不得删除已售订单/marker。源码回退只反向应用本轮相对保护基线的补丁，不能git reset/clean清理用户原有修改。

## 六、验证与限制

最终结果保存在`performance/results/attempt-phase-b-20261030_092037`，共112项通过，失败/错误/跳过均为0：

|验证|数量|结果|
|---|---:|---|
|相关单元/H2事务/原有功能回归|98（新增34、原有64）|通过|
|真实MySQL迁移/行锁/截止/两实例/恢复|5|通过|
|新协议真实Redis鉴权、Controller与MySQL/库存集成|3|通过|
|旧批量调度/Armory/库存补偿集成回归|6|通过|
|前端TypeScript类型检查及Vite构建|2项构建检查|通过；既有包体积warning未作额外优化|

真实MySQL suite约26.9秒，新协议集成约1分25秒，旧集成约2分12秒；不是负载窗口或容量指标。每组独立schema/账号，涉及Redis的组独立空DB。最终各组cleanup.json确认schema与账号已删除、Redis剩余键0、所启动进程已退出。最后只读复查没有受控库、账号、Redis归属标记或测试进程遗留。

初次测试遇H2不支持合并ADD COLUMN语法和测试Mock默认空Map，与正确的“查无记录返回null”不一致。已改为兼容的独立ALTER并修正Mock，保留初次失败日志，之后全部通过；没有跳过失败用例。

verification.json、unit/各类XML、mysql/durable/legacy下XML及tests.log、final-snapshot.json、redis-final-stock.json、command.json、database-phases.json、cleanup.json均已保存。code-identity.json核对最终测试前后生产源码/资源/类文件未变；phase-b-files.json标明仅12个已有文件被修改、新增9个代码/测试/迁移文件，235个其他已有源文件/配置保持原SHA。没有Risk/Agent生成逻辑修改。

phase-b-only.patch以本轮开始时保存的含用户未提交成果的基线生成，而不是以Git HEAD生成；只包含本阶段修改。反向应用的只读`git apply --reverse --check --ignore-space-change`验证通过，未实际回滚。以后如用户继续编辑，应重新检查冲突，不能盲目覆盖新成果。

本轮没有高负载、TPS对照、云部署、容器生命周期操作或现有数据库迁移，未开启新模式用于开发/生产流量。

新测试用可控时钟、数据库行锁屏障、提交失败注入、显式时间戳模拟11/31秒处理年龄，不通过sleep制造截止竞态。真实集成复用现有Armory测试的开售等待，未改生产时钟或投机预填资格。

真实集成包含Controller/@Valid、真实Redis Token鉴权、提交后独立查询、真实Lua/16桶/MySQL订单与Outbox、5秒终态及Redis已成功/MySQL限购失败后的单次补偿。集成测试手动控制claim/process以定位边界，不等同于持续自动调度性能测试。

已知限制：未进行真实双JVM kill/restart、持续负载和故障网络长时间测试；双Store实例/独立连接+租约故障注入不能替代这些验收。不能在线任意切换runtime_protocol并留下旧进程继续接纳，启动检查不能替代全节点切换步骤。未实现同SKU资格有序、REVIEW缓存或跨桶分配，不声明它们已解决。只读检查现有专属Redis为7.2.0、cluster_enabled=0，16桶同SKU使用同一hash tag，多桶Lua在同槽可行，但O(16)操作成本尚未实测，具体协议在E实现。新可靠登记增加HTTP数据库成本；尚无新的HTTP QPS/HELD TPS结果，不能把5秒超时或快速拒绝当吞吐改善。容量协调热点、历史特征IO与全局负载估算仍待后续阶段验证。
