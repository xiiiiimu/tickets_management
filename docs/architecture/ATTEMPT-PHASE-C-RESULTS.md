# 阶段C完成记录

本阶段完成按SKU/装配版本的资格发布顺序→Lua预占顺序，允许MySQL订单确认并行。默认开关关闭；未在现有开发库迁移/激活，未开展性能压测，不进入D/E。

设计及事务/恢复论证见[ATTEMPT-PHASE-C-DESIGN.md](ATTEMPT-PHASE-C-DESIGN.md)。本轮以`.tmp/attempt-phase-c-baseline-20261030_100727`保护原有未提交源码；未reset/clean/stash、未提交、未操作容器生命周期。

## 实际改动

|文件（Java项目下）|作用|
|---|---|
|ticket/ReservationQualificationOrder.java（新增）|资格发布校验、按SKU/版本的持久序号、队首校验及完成/补偿推进|
|ticket/ReservationV2Transactions.java|prepare末尾发布；独立Lua短事务；原订单确认事务不持有lane锁；abort补偿后推进|
|inventory/InventoryService.java|读取并校验已有HELD marker、身份、数量、版本、桶及原expiresAt；不重扣、不续期|
|ticket/ReservationV2Service.java|Pending让出worker，复用PROCESSING和既有裁决；不abort排在后面的有效资格|
|ticket/ReservationAttemptStore.java|持久模式一致性检查；按state/token条件让出租约，旧worker不能清空新owner|
|ticket/ReservationRuntimeRecovery.java|既有恢复器在abort成功后同事务推进未完成turn，不能跳过前驱|
|resources/db/migration/V111__qualification_lua_order.sql（新增）|新增模式字段、序号/完成标记、唯一索引、SKU顺序表|
|resources/schema.sql|fresh schema的等价结构，仍可重复建表；不使用无条件ALTER作启动初始化|
|resources/application-reservations-v2.yml|独立ordered-lua开关默认false|
|test/.../ReservationQualificationOrderTest.java（新增13项）|序号/回滚/顺序、跨SKU并行、确认并行、补偿、故障、旧token、两实例发布|
|test/.../QualificationClaimBoundaryTest.java（新增4项）|PROCESSING让出/重领、模式检查、服务Pending不重风控不误abort|
|test/.../inventory/OrderedHoldReadTest.java（新增4项）|原期限/桶读取与marker字段安全拒绝|
|test/.../integration/OrderedQualificationIT.java（新增3项）|真实Armory/风控/Lua/MySQL确认并行、丢回复补偿、租约恢复|
|test/.../QualificationOrderMysqlTest.java（新增3项）|真实V111迁移保留旧配置、InnoDB双实例连续序号、回滚和独立SKU|

另新增`performance/qualification_order_integration.py`，只编排归属校验的临时集成测试数据，不调用性能负载生成器；原有Harness/性能工具未修改。

源码清单、原SHA比较及仅C补丁见原始目录`phase-c-files.json`/`phase-c-only.patch`：7个原文件修改、7个新增Java/测试/资源文件，242个既有src文件SHA保持不变，无删除。Lua、Armory源码、风险生成/Agent、支付/出票、HTTP鉴权及限流未修改。原协议批量claim保留，不把批量领取当资格屏障删除。

## 状态和可靠性边界

HTTP仍按B先可靠登记WAITING、提交后202；5秒只限制首次领取，不变成PROCESSING截止。资格序号在可靠意图事务末尾发布，只有持久PASS/REVIEW裁决可发布；尚未判定或BLOCK不能提前占序号。

同SKU按发布的线性化顺序竞争Lua，PASS不具有绝对优先权。不同SKU不共用顺序锁。Lua及顺序元数据提交后立即释放lane，确认事务可并行；不得将这句话解读为“不等待Lua短事务的元数据提交”。

序号与意图一同回滚，不留无主序号。队首崩溃需租约恢复，后序不越过未知结果；明确失败先确认释放/ABORTED墓碑再推进。Lua成功但短事务回滚仍保有REQUESTED意图及旧cursor，可以幂等恢复或补偿；短事务已提交则恢复读取原HELD marker，不再执行reserve Lua。迟到旧请求不能越过墓碑重新扣库存。订单完成仍由原16桶/限购/预占/订单/风险记录/Outbox事务保证，支付期限取原Lua heldAt+600000。

## 分层测试及耗时

**34个不同测试用例通过，失败/错误/跳过均为0。** 不是“阶段B112项全跑一遍”。因边界补充存在少量针对性重跑，累计执行48项次，不能把项次当不同用例数。

|层级/范围|不同用例|实际执行时间|
|---|---:|---|
|L1 首次顺序/事务边界（含7项旧事务回归）|19|JUnit 8.007秒；测试命令墙钟34.47秒；受影响主类编译16.02秒、测试编译12.97秒|
|L1 增加claim/让出边界（12项顺序复验+4项新增）|16项次|JUnit 7.744秒；命令39.20秒；主类编译16秒、测试编译23.84秒|
|L1 最终持久裁决发布保护（2项复验+1项新增）|3项次|JUnit 4.185秒；命令21.56秒；单主类编译6.61秒、单测试类8.58秒|
|L1 库存marker读取安全边界|4|JUnit 2.722秒；命令20.66秒；单测试编译7.34秒|
|L2 真实MySQL/Redis集成|3|JUnit suite 78.372秒；Maven约97秒；schema初始化11.078秒|
|L2 真实MySQL增量迁移/两实例顺序|3|JUnit 11.894秒；Maven25.033秒；测试编译9.81秒|

耗时来源：真实集成沿用既有Armory fixture，3个场次各等待约15秒开售；3个方法分别19.452/16.798/16.305秒，总suite还包含Spring上下文启动等成本。测试方法没有依靠sleep制造并发顺序，而使用屏障、闩锁、事务回滚和故障注入。编译/测试命令墙钟包含JVM/Maven启动和插件开销，不是业务处理延迟。没有延长技术超时、降低断言或跳过失败用例。

最终补充的发布前持久裁决保护在第一次真实集成之后加入，只重跑了直接相关的3项发布/回滚/并发L1；随后真实MySQL发布测试也验证了这段最终代码。没有为该单一保护重跑不相关的全部集成。

## 原始数据、清理及复现

- `performance/results/attempt-phase-c-20261030_101808`：真实库存集成3项、各次L1日志/命令、最终源码SHA、仅C补丁、设计及结果。
- `performance/results/attempt-phase-c-20261030_102612`：V111真实迁移与InnoDB并发3项。
- 两组cleanup.json均确认专属schema/账号不存在、所启动进程退出；含Redis的组剩余键0。Docker生命周期操作、性能负载次数均为0。
- `git apply --reverse --check --ignore-space-change .../phase-c-only.patch`只读校验通过，没有实际回退。

当前工作区复现（需要已有专属MySQL 13306/Redis 26379及项目独立.env，不启动容器）：

```powershell
# 已编译情况下，只运行指定L1，不触发全量：
E:\maven\apache-maven-3.9.12\bin\mvn.cmd -o -f E:\credit_risk\ticket-risk-platform\pom.xml '-Dtest=ReservationQualificationOrderTest,QualificationClaimBoundaryTest,OrderedHoldReadTest,ReservationAttemptTransactionTest' '-DargLine=-Xmx256m -Duser.timezone=UTC' surefire:test
# 新建/校验归属/清理专属数据，仅指定L2方法：
python E:\credit_risk\performance\qualification_order_integration.py
python E:\credit_risk\performance\qualification_order_integration.py --mysql
```

测试脚本复用当前工作区的依赖classpath/既有编译产物及阶段B的Maven启动命令；不是可脱离仓库环境直接运行的发行包。受影响编译的确切脚本和选择器保存在结果l1目录。

## 剩余风险、回退和后续

- 严格顺序有队首阻塞及同SKU Lua短事务提交串行成本。本轮只证明功能并行边界，不提供QPS/HELD TPS提升数字。尚未执行L3全量、真实双JVM kill/restart、故障网络、持续负载或AB；不能用两实例对象测试冒充双进程验收。
- 本阶段没有更改全局活动上限4、容量512、线程池/连接池大小。后序让出后仍计为PROCESSING，会使用既有活动容量；这是保持边界的保守选择，非吞吐优化。调度恢复/让出成本留待统一性能验收。
- D尚未实现：仍用现有完整风险算法；不声称已实现MySQL画像安全回源、REVIEW AGREE/REJECT缓存或快复核。C开关不应独立用于正式新业务放行。
- E跨桶quantity尚未实现，本轮仍为原单桶marker/释放协议；不宣称总量够时不存在分桶碎片。
- 新顺序游标丢失/不一致时拒绝重建，不通过跳号恢复。Redis库存/marker丢失仍需要既有Armory/可靠意图核对；本轮不模拟或保证任意Redis数据破坏后的自动恢复。
- V111是附加迁移，尚未在既有库执行。启用须全节点兼容升级、停接纳排空、审核持久ordered_lua_enabled=1并启用独立配置；不能在线混跑新旧语义。
- 回退必须先停接纳，排空/安全恢复新资格和REQUESTED意图，核对库存订单，再审核持久模式切0、关闭ordered-lua。附加表列保留，无需DROP；不可删除未完成lane或marker来强行回退。

下一阶段D接入用户确认的画像回源及REVIEW快速复核，资格发布点复用C，不改顺序协议。本轮到此停止。
