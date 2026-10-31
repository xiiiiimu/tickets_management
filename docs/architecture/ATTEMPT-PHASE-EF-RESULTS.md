# 阶段E/F完成记录

## 交付状态

E实现并验证同SKU跨16桶原子分配、单次限额、向量释放/退款/Armory恢复、售罄任务终止；F完成一次全量回归及针对性修复验证、真实MySQL/Redis集成、真正进程中断和双JVM恢复、一次ABBAAB六轮对照。所有新功能默认关闭，未迁移或激活现有开发库，未自动提交、部署或追加调优。

详细设计：[ATTEMPT-PHASE-EF-DESIGN.md](ATTEMPT-PHASE-EF-DESIGN.md)。B/C/D设计及缓存键、TTL、失效策略继续以各阶段结果文档为准，本轮未修改长期画像或Agent业务逻辑。

原始交付目录：`E:\credit_risk\performance\results\attempt-ef-ab-20261030_132006`，包含COMPARISON.md、TEST-RESULTS.md、comparison-summary.json、每轮HTTP/jobs/resources/JFR/锁采样/清理记录、源码SHA256及phase-ef-only.patch。补丁相对进入E/F前的快照，`git apply --reverse --check`已通过；不包含更早阶段的大量用户未提交开发成果。

## 架构变化与正确性

|进入E前|E之后|
|---|---|
|整笔quantity必须来自单个桶，可能总量足够却失败|Lua先核对16桶总量，全部成功或全部失败，再持久化非零分量|
|单桶分配表头|旧表头保留；新header=-1，allocation_item记录真实桶分量|
|单桶释放和恢复|按marker协议分派，新旧订单均支持幂等释放/恢复，保持原支付到期时间|
|统一请求上限100、用户累计限购|保留原上限与累计口径，增加SKU单次限额1至100，发布到Armory配置|
|售罄由各worker独立发现|增加有界提示扫描，重新原子确认0库存并逐attempt写墓碑，保护已成功Lua/订单|

202仍在可靠WAITING登记提交之后返回，5秒仅限制截止前领取。PROCESSING没有新增固定10/30秒业务期限，保留技术超时和租约。资格发布顺序→Lua有序→MySQL确认并行的边界不变；不同SKU独立。没有修改旧库存Lua、删除Armory校验、扩大业务线程池或恢复单SKU MySQL库存热点行。

向量SQL更新按桶号排序、需要活动事务，任意桶CAS失败全部回滚。Redis成功SQL失败仍依赖原可靠意图、幂等marker、墓碑和恢复/补偿。释放仅一次恢复所有实际分量，重复退款/释放不增加库存。Armory恢复将明细作为权威，不完整时拒绝猜测库存。

售罄与释放的先后由同槽Lua裁决；旧任务不因补库存复活。WAITING先遵守5秒超时；正在Lua事务中的任务用SKIP LOCKED避开；已发布的非队首资格先封堵迟到预占，再等待安全推进，避免序号空洞。提示本身可丢失，只影响终止速度，不影响最终原子库存校验。

## 修改清单

以下路径相对ticket-risk-platform；完整机器清单见交付目录change-list.json（29个Java/资源/测试文件，其中18个生产文件）。

|文件|作用|
|---|---|
|src/main/java/com/ticketing/inventory/InventoryService.java|向量Lua分派、marker校验、兼容释放及配置发布|
|src/main/java/com/ticketing/inventory/StockBucketService.java|向量明细、排序CAS、事务回滚、一次性恢复|
|src/main/java/com/ticketing/sale/TicketSaleArmory.java|新协议/限额绑定与向量恢复，保留原Fence|
|src/main/java/com/ticketing/repository/jdbc/JdbcTicketInventoryRepository.java|读取单次限额，兼容旧结构默认值|
|src/main/java/com/ticketing/ticket/ReservationIntent.java|携带16桶分配向量|
|src/main/java/com/ticketing/ticket/TicketSku.java|增加single_purchase_limit字段|
|src/main/java/com/ticketing/ticket/ReservationAttemptStore.java|V113及持久协议一致性校验|
|src/main/java/com/ticketing/ticket/ReservationSoldOutSweep.java（新增）|有界安全售罄扫描|
|src/main/java/com/ticketing/ticket/ReservationV2Service.java|复用原控制循环发出/处理售罄提示|
|src/main/java/com/ticketing/ticket/ReservationV2Transactions.java|区分售罄、数量不足和单次超限结果|
|src/main/java/com/ticketing/event/CatalogAdminService.java|首次装配前配置SKU单次限额|
|src/main/java/com/ticketing/infrastructure/AdminController.java|管理员限额配置接口|
|src/main/resources/lua/reserve_ticket_vector.lua（新增）|同槽跨桶全有或全无预占|
|src/main/resources/lua/release_ticket_vector.lua（新增）|向量幂等释放及ABORT墓碑|
|src/main/resources/lua/mark_sold_out_attempt.lua（新增）|再次校验0库存、版本、期限、HELD保护|
|src/main/resources/db/migration/V113__vector_stock_allocations.sql（新增）|附加字段和allocation_item表|
|src/main/resources/schema.sql|全新测试库结构同步|
|src/main/resources/application-reservations-v2.yml|vector-reservations默认false|
|src/test/java/com/ticketing/inventory/VectorBucketTest.java、event/CatalogSingleLimitTest.java（新增）|向量事务/并发释放/限额边界|
|src/test/java/com/ticketing/integration/VectorReservationIT.java、VectorRaceIT.java（新增）|真实SQL/Redis碎片库存、补偿、售罄竞态|
|src/test/java/com/ticketing/integration/VectorFulfillmentIT.java（新增）|本地支付与超时竞争、出票幂等、退款恢复|
|src/test/java/com/ticketing/integration/VectorProcessRecoveryIT.java、benchmark/VectorWorker.java（新增）|真实强杀及双实例恢复|
|src/test/java/com/ticketing/ticket/VectorMigrationMysqlTest.java（新增）|旧数据不变、协议混用拒绝|
|src/test/java/com/ticketing/ticket/AttemptQuickMysqlTest.java、QualificationOrderMysqlTest.java|修复测试条件装配，不改业务断言|
|src/test/java/com/ticketing/integration/AttemptAcceptancePerformanceIT.java（新增）|真实HTTP及后台统一计时和不变量验收|

performance新增vector_lua_checks.py、vector_integration.py、ef_mysql_regression.py、ef_owned_integration.py、ef_redis_cleanup.py、attempt_acceptance_ab.py、summarize_attempt_ef.py。清理工具只处理自己成功占有的测试库，未知活跃键或归属变化立即失败。

## 测试结果与耗时

详细列表、原始失败和耗时见交付目录TEST-RESULTS.md。全量编译/回归仅执行一次：303项，297通过、2个外部MySQL条件装配失败、4条件跳过，123.5秒。修复条件注解后针对性真实MySQL28项全部通过，未再次跑303项。部分归档XML是早先运行，不能合计冒充该次全量数量。

后续真实库：可靠生命周期3、有序预占3、REVIEW4、批量领取旧链路11、旧Armory/交易28、库存CAS2，均通过。E碎片库存/故障/恢复四项在修复一个错误注入夹具后全部通过；售罄竞态2、支付/超时/退款1、真实强杀双JVM恢复1均通过。L1单次限额4、向量/事务边界及本地Redis6场景通过。前端类型检查通过21.12秒。

最长批量回归207.562秒JUnit（Maven约230秒）主要包含旧夹具每场次等待约15秒开售；线程快照证明当时在sale夹具sleep。未加超时或削弱断言。曾出现清理DBSIZE瞬时1、复查已空，未捕获原键名；保留原失败记录，新增保留owner的有界SCAN清理并验证，最终无残留。

## 六轮性能结果

|轮次|模式|HTTP QPS|202数/50|HELD TPS|HELD数|WAITING P95秒|成功HELD端到端P95秒|
|---|---|---:|---:|---:|---:|---:|---:|
|1|A单桶|19.91|15|1.66|14|4.989|6.547|
|2|B向量|22.92|14|2.47|14|2.717|3.671|
|3|B向量|24.65|15|2.25|15|3.597|4.857|
|4|A单桶|28.21|15|2.38|15|3.404|4.798|
|5|A单桶|19.00|15|2.16|15|3.941|5.022|
|6|B向量|18.41|16|2.41|16|3.352|4.465|

中位数：HELD TPS 2.159→2.415（+11.85%），成功HELD端到端P95 5.022→4.465秒（-11.09%）。HTTP总QPS 19.91→22.92，202成功接纳QPS 5.97→6.42。不能将34至36笔快速入场拒绝计入成功吞吐。

每轮均为50唯一POST、10客户端并发、3预热和4幂等重试另计，新JVM/隔离库/空Redis。A/B只切E协议，B/C/D均开启。两组各3样本，TPS和P95范围重叠，GC/环境有波动，不能证明稳定容量提升。该对照是quantity=1充分库存下的协议代价比较；quantity>1的跨桶能力以功能/竞态测试验证，不冒充性能收益。

合计300初始POST：A接纳45、成功44、QUEUE_TIMEOUT1、429为105；B接纳45、成功45、429为105；没有503、其他错误响应、未发送或未终态。超时占已登记A组2.22%、B组0%，入场拒绝两组均70%。WAITING分位只包含已领取任务，超时另列；PROCESSING包含持久调度等待，不是线程CPU执行耗时。

PASS/REVIEW资格、Lua、确认事务、连接池、GC及锁采样逐轮保存在batch.json/comparison-summary.json；REVIEW独立用户负载均为miss，不能拿该0命中率评价重复用户缓存收益。终态观察间隔目标200ms但非硬误差上界。Hikari计时包含预热及背景活动，不能解释为每笔锁单独占连接等待。

资源：测试期间最小可用物理内存1443MiB，未低于1GiB门槛。Hikari采样活跃最高10、等待最高7，锁采样仍主要指向tb_reservation_dispatch；采样行不是独立等待事件，现有证据不支持本轮自动扩大连接池。

## 剩余风险及回退

1. 本轮保留了冷worker启动期间触发现有5秒事务技术超时的证据：安全失败并补偿，不保证都能恢复成成功订单。就绪后双JVM恢复通过不掩盖这一可用性风险。
2. 跨桶向量增加每次读桶和分配明细成本；严格同SKU Lua顺序、协调事务及装配屏障仍有吞吐代价。没有承诺5000 TPS，更没有通过5秒超时或拒绝制造HELD吞吐提升。
3. 单次限额以SKU配置并随所属场次隔离，尚无场次级继承默认值；Redis Cluster完整部署未实测，21 key同槽约束在代码及单实例验证。
4. 短而有界的顺序/版本事务仍包含必要Redis往返；没有声称消除了所有跨系统事务等待。数据库实际部署/长周期恢复、付费Agent和外部支付不在本轮范围。
5. 保留协议3兼容代码和allocation_item直到所有向量订单及退款窗口结束；不能直接回退不认识vector的旧二进制。V113没有在已有库执行，不需要清理或倒写现有业务数据。

回退详见设计文档：停止新接纳、排空/恢复并核对后，再审核切换开关和持久协议，重新装配READY；保留附加表和历史分配。仅回退E/F源码可使用归档补丁及快照，但须先校验文件SHA，禁止覆盖后续用户修改。本轮只验证反向补丁可应用，没有实际回退或删除数据。

最终预检确认无专属临时库/账号、无Redis归属标记、无本轮Java进程；原有Docker服务保持运行，未停止用户原有容器。功能默认关闭，后续实施需独立选择，不自动继续优化。

## 复现命令（本轮已执行完，不会自动重跑）

```powershell
cd E:\credit_risk
# 先准备项目原有离线Maven依赖、target/classes/test-classes、benchmark-classpath.txt。
# ComparisonDatabase由performance/ComparisonDatabase.java编译到.tmp/fullchain-classes。
# 各次完整Java启动命令保存在结果子目录command.json；环境密码只从独立.env读取。
python performance/vector_lua_checks.py
python performance/vector_integration.py
python performance/vector_integration.py --races
python performance/vector_integration.py --fulfill
python performance/vector_integration.py --process
python performance/ef_mysql_regression.py
python performance/ef_owned_integration.py
# 只在再次明确安排对照时执行，不因生成报告自动执行：
python performance/attempt_acceptance_ab.py --execute-approved
# 纯离线汇总，可反复运行，不会发业务请求：
python performance/summarize_attempt_ef.py performance/results/attempt-ef-ab-20261030_132006
```

测试脚本复用先前保留的`.tmp/attempt-phase-b/unit-command.json`，该命令只启动Surefire，不能替代编译。原始依赖/工具目录与本轮版本标识均保留，不以Git HEAD代表包含大量未提交成果的实际代码版本。
