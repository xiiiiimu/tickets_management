# V3 START调度与HELD关键路径：代码诊断和受控设计

本轮仅代码审查和既有数据离线分析；没有修改生产实现、协议2环境或阈值，没有发起业务请求或性能负载。此前联合负载约5秒后被监控保护停止，不能当完整30秒容量试验。

证据目录：`docker/credit_risk_v3_bench/runs/54090d45/benchmarks/joint-da767a107d`。运行JAR SHA-256为`448425a6fa1f62afe2a3def7878e573a5ef46f4f3f0781ba27662439e48d1c81`。上轮核对的关键源码与构建快照一致。

## 1. 最严重的实际等待及证据边界

Hikari取连接计时差分1096次，累计1262.578秒，平均1151.99ms，滚动最大6836.78ms。直方图差分的P50所在桶626.35～715.83ms，P95在2863.31～3221.23ms，P99在4294.97～5726.62ms。连接持有计时1096次，平均238.34ms。这个证据确认本轮应用存在严重连接获取等待；事后pending=0不能排除负载期间争用。

这1096次属于整个应用，包括HTTP计划读取、后台任务、worker等。**不能把1151.99ms直接从每笔starter.begin的4960.42ms中扣掉，剩下称为SQL执行。** 负载期线程等待及连接占用分布没有完整采到，也不能推出应扩大连接池。

计划缓存差分578次miss、29次hit，命中率4.78%。`TicketSaleArmory.cachedRuntimePlan()`在miss时直接执行`runtimePlan()`的REQUIRES_NEW事务，无同key合并加载。该事务内锁读装配行，再同步执行Redis版本/manifest、库存键存在性、配置校验。HTTP入口和worker共用Hikari30。缓存时间戳取加载前的now，加载超过500ms时写入的缓存可能立即过期。这些是确定的代码行为；但缺少按key加载开始/结束轨迹，不能将578次miss全部称为同一轮并发击穿，也不能证明它解释了所有连接等待。

GC差分3次、总暂停86ms，最长快照68ms；现有证据不支持GC直接解释数秒平均等待。MySQL全局行锁等待从1次/69ms增至6次/1050ms，即增量5次/981ms；它跨测试准备及收尾，且缺锁对象采样，不能指认dispatch或某个库存桶为本轮主热点。累积digest的COMMIT平均43ms包含旧任务和准备数据，不是16笔HELD的提交延迟。

## 2. 精确到现有计时能力的路径拆解

### starter.begin：平均4960.42ms，最大7044.57ms，16笔

`V3JobStarter.begin()`使用REQUIRES_NEW、READ_COMMITTED、3秒TransactionTemplate，每次普通新请求执行：

1. `SELECT * FROM tb_reservation_job WHERE request_id=? FOR UPDATE`：request_id唯一索引，命中时锁本请求。
2. `SELECT job_id FROM tb_reservation_request_alias WHERE request_id=?`：本请求别名。
3. `SELECT request_id FROM tb_reserve_request WHERE request_id=?`：兼容旧交易幂等。
4. `SELECT * FROM tb_reservation_job WHERE active_owner=? FOR UPDATE`：user:session唯一owner。
5. `SELECT CURRENT_TIMESTAMP(3)`：数据库租约时间。
6. `INSERT tb_reservation_job(...state='PROCESSING',attempt_protocol=4...)`：request、reservation、order、active_owner唯一约束。
7. `queue.get(attemptId)`：按主键读出当前租约行。
8. COMMIT及连接恢复。别名/接管分支有额外查询；唯一冲突最多重试两次。

不存在id=1协调行锁，也没有Redis、画像或Agent调用。事务内的排队可能来自获取连接、JDBC事务准备、唯一键/同owner行竞争、SQL往返、提交或线程调度。**现有begin只有整体timer，没有begin/body/completion、逐SQL及COMMIT边界，以上各项的具体毫秒仍未知。** 配置了3秒事务超时不等于整个方法墙钟必小于3秒：取连接可在事务正文前等待，且驱动enableQueryTimeouts=false不会主动取消已执行语句。不能据总耗时反推一次SQL执行了5秒。

不建议立即删除查重、owner锁或改成INSERT-first：它们和旧请求兼容、alias以及timeout-fence的锁顺序有关。安全候选包括已插入行的返回构造/RETURN读优化，但最多省一条SQL，不能宣称解决4.96秒。

### processV3：平均4401.30ms，16笔

|互斥子阶段|平均ms|主要调用|
|---|---:|---|
|plan_lookup|708.93|cachedRuntimePlan，miss时取得连接并执行带锁门禁事务|
|risk_classify|1349.28|AttemptRiskProfiles、ReviewQuickCheck|
|decided|528.98|job主键FOR UPDATE、ENTRY审计、admission更新、重读、COMMIT|
|prepare|402.80|Armory共享门禁、job/租约检查、subject guard初始化、意图、资格发布|
|reserve_and_held|1410.64|有序预占事务和最终确认事务|
|其他框架/串联开销|约0.67|上述互斥子段之外|

这些是同一批16笔的平均墙钟，可以核对总和；不是P95，不能对分位数做同样加法。

prepare事务独立计时：begin平均3.91ms、body220.93ms、completion176.62ms、total401.46ms。completion包含提交/回滚和事务清理，不是纯fsync。这说明后续prepare阶段的取连接/事务开始已经远小于早期阶段，不能将池等待均摊到全部事务。

reserve_and_held内部，有序事务平均501.05ms，最终HELD事务909.45ms，外层余量约0.15ms。库存Redis reserve操作平均30.99ms，MySQL stock操作平均216.84ms；两者是内部子步骤，不能再与1410.64ms相加。

风控不是旧的全量历史特征链：`AttemptRiskProfiles.read()`依次读取USER/DEVICE/IP Redis缓存提示，再用`JdbcRiskProfileRepository.findSubjects()`的OR条件一次回源正式画像，确保最新BLOCK权威；`ReviewQuickCheck.base()`执行请求频率Lua和黑名单EXISTS；4笔REVIEW均为cache miss，另有缓存锁、快速频率、同用户同场次冲突COUNT、设备标记及缓存发布。没有Agent调用。1349.28ms未进一步拆成画像SQL取连接、SQL执行和各Redis往返，不能编造比例。

prepare/ordered/HELD都保留`runtimeSku`的装配共享锁；锁对象为本场次assembly/event/session读取行，和装配/恢复写操作互斥。job、intent按各自主键/唯一request锁定；subject guard按USER/DEVICE/IP范围共享锁防最新BLOCK竞态；库存锁为`tb_ticket_stock_bucket(sku_id,bucket_id)`，限购为user+sku行。没有证据将共享装配锁认定为串行互斥锁；本轮也没有装配写操作与负载重叠的证据。

## 3. 最值得改的两个位置

**位置一：`TicketSaleArmory.cachedRuntimePlan()`的V3调用路径。** 优先设计按session+sku的有界single-flight，避免同key并发miss各持一个连接。不得缓存身份/放松风控，不改变prepare、ordered、HELD三处权威门禁。不直接把时间戳改成加载结束：那会改变TTL的陈旧性边界。合并加载必须明确加载超时、失败不缓存、等待者有界、版本切换后不发布旧结果、无空闲连接时安全503。每个返回仍检查Redis版本/fence及当前销售窗口；同key故障不能串住其他SKU。不采用全局锁，不修改Hikari大小。

收益机制：减少重复数据库事务和持连接期间的Redis往返，给begin/正式画像/HELD留下连接。578次miss和秒级池等待是优先级依据，不是收益百分比承诺。仅将缓存时间戳后移或给所有miss加同一把锁，均不作为可上线修复。

**位置二：`V3StreamWorker.run/pollOnce/handle/startOwned()`的控制与执行边界。** 分离读取/超时处理与业务执行，消除无效任务处理和完整业务任务共享槽位的耦合。它首先改善可观测性、超时清理和业务槽位利用，不能绕过global activeLimit增加合法START。

`V3JobStarter.begin()`和`processAttempt()`是后续事务优化的靶点，但必须先定位取连接、body、completion及逐SQL时间，不能据4.96秒删查重，或据4.40秒合并独立可靠事务。

## 4. 有界两阶段调度设计（本轮不实施）

### 重要约束

全局activeLimit仍16。如果16笔都已合法START且正在执行，即使另加START线程，第17笔也只能得到FULL。不能通过释放active、提前START后放入大队列、重置deadline或重新包装PROCESSING获得吞吐。仅把Stream提前读入内存不会提升HELD能力，反而可能把等待挪到PEL/本地队列。

### 阶段A：控制、预取和到期裁决

- 沿用一个控制线程，单实例有界staging窗口不超过workers（当前16），只保存Stream record引用。实际全局容量/用户在途仍由Redis Lua裁决，本地窗口不是权威。
- 读后留在PEL，不ACK。staging满即停止预取，不从Stream无限搬运到JVM。不同SKU共享有界资源而非全局串行库存。
- 没有业务执行名额时，不调用可授予START的脚本。若要独立清理过期任务，必须为同一个`v3_start.lua`设计显式`expiry-only`模式：只允许WAITING到QUEUE_TIMEOUT；Redis时间仍是唯一截止判断，未过期返回WAIT，PROCESSING永不变超时。**不能拿Java本地时钟判断“看起来已过期”后直接调用普通START，否则可能意外提前开始任务。** 这是需要额外测试的脚本协议扩展，不是已完成实现。
- 超时结果交给独立、严格有界的持久化通道；MySQL fence提交后才settle+ACK。该通道满时留在PEL并停止进一步搬运，不丢任务。其数据库并发也必须有界，不能通过大批超时INSERT继续挤占业务连接。

### 阶段B：就绪执行名额→原子START→MySQL begin→原业务→settle

- 先取得一个真实可执行的业务名额，再调用原START；无业务排队容量，不提前批量START。
- START仍用Redis时间、5秒deadline、global activeLimit和token原子裁决。FULL则归还本地名额，保持WAITING/PEL；TIMEOUT转阶段A的持久化结果处理；START/TAKEOVER才进入begin。
- 不增加work数量，不释放global active直至现有MySQL终态settle。只记录本地执行所有权，不把JVM计数变成多实例权威。
- START后begin尚未完成期间也必须能追踪该token/PEL，不能依赖当前仅在begin返回后才注册running的方式。Redis续租与MySQL续租分开，begin异常/结果不明不能无限续租；使用已有技术超时和租约恢复。成功begin后按MySQL token围栏继续正常心跳。
- 提交拒绝、控制线程故障、返回结果不明时均不得将已START任务改回WAITING或报QUEUE_TIMEOUT，保留PEL和租约重驱。

### 状态和故障恢复

|边界|持久/Redis状态|恢复与ACK|
|---|---|---|
|读入staging前后崩溃|WAITING，Stream或PEL|其他节点重读/idle claim，原deadline不变；过期才走TIMEOUT|
|START与到期同时发生|Lua原子决定PROCESSING或QUEUE_TIMEOUT|只有一个成功；expiry-only不得覆盖PROCESSING|
|START成功，begin前拒绝或崩溃|Redis PROCESSING+token，MySQL可能无行|不ACK，不返还成WAITING；租约到期TAKEOVER后begin|
|begin提交结果未知|MySQL可能PROCESSING|查MySQL并核token；不可确认则留PEL；不能以5秒deadline补判失败|
|MySQL已PROCESSING，超时fence迟到|DELEGATED|现有任务继续，不写超时终态|
|intent已提交、Lua前故障|持久REQUESTED意图|原恢复链重驱，库存还未扣|
|库存Lua后、HELD提交前故障|Redis marker+MySQL意图|原查询marker、补偿/恢复、墓碑保护，不重复扣库存|
|HELD提交后、ACK前故障|MySQL终态已权威|重读终态settle并ACK，不重复订单，不补偿成功订单|

协议4持久表不增加新状态；READ/PREFETCH/EXECUTING是本地观测阶段，不伪装成新的可靠受理保证。5秒从Redis原始受理时钟算，不从读取/移交时重算。

### 为什么本轮不直接实施两阶段

全局活动名额16不能变，当前16笔有效任务占槽9.45秒的根本执行成本不会因调度拆分消失；独立过期清理甚至可能增加数据库争用。新方案还涉及expiry-only脚本、START后begin前续租、两阶段提交拒绝和PEL恢复。现有数据无法证明这部分能显著提高真实HELD TPS。按用户“证明安全且收益明确后再实施”的前提，本轮交付设计，不实施大范围调度或缓存改造。

## 5. 最小实施及验证顺序

1. 先补齐begin边界观测：复用AttemptTransactionTiming，分别记录connection/事务准备、body、completion；逐SQL采用固定operation名而非requestId高基数标签。记录Stream读取返回、提交和handle开始，填补已知观测缺口。正确性测试不需要正式洪峰；单笔与少量并发不得作为性能容量结论。
2. 缓存合并加载单独开关、默认关闭，限定V3非权威计划读取。L1覆盖同key合并、不同SKU并行、加载失败、等待超时、版本切换和关闭开关原路径；L2验证三处真实门禁及Armory恢复，不能只验证mock调用次数。
3. 若缓存竞争下降后业务槽位仍因无效任务长期占用，再实现两阶段。L1用屏障/可控时钟覆盖无业务名额不START、窗口满不读、5秒竞态、提交拒绝、异常不ACK；L2覆盖双实例active上限、用户在途、租约接管、begin未知、timeout与begin竞争、Lua后崩溃、HELD后ACK前崩溃。数据库与Redis均须独立隔离，不能用协议2开发库。
4. 不在本轮执行正式5000 rps。后续经授权用固定买家/库存/提交率、同JVM参数与采集开销对照，不能改deadline、activeLimit或用更低202掩盖结果。

HELD改善的验收同时要求：固定窗口MySQL COMMIT可见的唯一HELD数量和稳态TPS增加；同一需求下点击至HELD的P95及积压不恶化；新受理数、QUEUE_TIMEOUT、429/503、CPU、池等待、事务阶段分别展示；库存/意图/订单/Outbox一致、零重复claim/HELD，最终PEL/队列清空。只有QUEUE_TIMEOUT减少、读Stream更快或PROCESSING增加而HELD不增，不算性能成功。

## 6. 本轮交付和测试边界

新增此设计文档与离线分析脚本`performance/analyze_v3_start_held.py`。原始数据不覆盖；机器可读差分另存`start-held-offline-analysis.json`。未改src/main、配置、Lua、迁移、线程池或连接池；未启停容器或连接数据库；没有性能优化代码，因此没有声称运行新方案正确性IT。只做Python语法检查和离线计算。RiskHistorySummary的08007未纳入本轮改造。

复现离线分析：

```powershell
python E:\credit_risk\performance\analyze_v3_start_held.py E:\credit_risk\docker\credit_risk_v3_bench\runs\54090d45\benchmarks\joint-da767a107d
```
