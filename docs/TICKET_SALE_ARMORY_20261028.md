# TicketSaleArmory 开售前装配实施记录

日期：2026-10-28。本批仅装配、版本发布、真实业务门禁与安全恢复。LOW 调度、活动/场次/SKU 动态策略继承与编辑、通用规则树仍未实施。

## 范围与兼容性

复用演唱会 tb_event → 场次 tb_event_session → 票档 tb_ticket_sku，以及原 tb_ticket_stock_bucket / tb_stock_bucket_allocation。新场次可以在首次开售且没有预占请求之前显式纳管。原有场次未纳管时保留原流程；没有自动迁移既有交易数据。

默认 ticketing.sale.armory.enabled=false。必须先应用 V103/V104，并在所有服务实例统一启用装配门禁、统一库存桶配置，再纳管新场次。支持原单桶与 16 桶；16 桶沿用 ticketing.inventory.bucket-count=16，不在线切换已交易 SKU 的库存拓扑。既有 SKU 若没有桶，原默认路径仍可运行。

未修改已有库存预占/释放 Lua 的文件、KEYS、ARGV、返回值与幂等语义；没有修改订单/支付/出票状态机。现有 /api/tickets/reserve 请求和响应结构不变。新增 API 仅在启用时出现在 /api/admin 下，沿用现有登录及管理员授权。

## 实施单元与真实文件

1. 装配基础：V104__ticket_sale_assembly.sql、schema.sql（新安装同时包含 V103）、SaleManifest.java、StockBucketService.java、InventoryService.java、三个独立装配 Lua，以及 SaleManifestTest.java。
2. 生命周期与业务接入：TicketSaleArmory.java、SaleArmoryController.java、TicketController.java、ReservationIntentService.java、ReservationTransactionService.java、RiskDecisionService.java、application.yml。
3. 真实故障测试：TicketSaleArmoryIT.java、SingleBucketSaleArmoryIT.java；TicketPlatformIT.java 只增加测试上下文关闭标记，防止多个测试容器组间遗留连接及定时任务。
4. 观测与部署：BusinessMetricsAspect.java、docker-compose.yml、Grafana 看板及本说明。

精确路径、原始文件字节、原索引、分组补丁及 SHA-256 清单位于 .tmp/armory-20261028。本轮起点包括上一批全部未提交成果；没有 reset、clean、stash 或批量暂存，没有创建混入既有成果的 Git 提交。补丁以本轮起点为基准，不能直接套在裸 Git HEAD 上。

## 生命周期与版本

新增表：

- tb_ticket_sale_assembly：场次状态、当前代次、已发布代次/摘要、操作编号、租约、错误及更新时间。
- tb_ticket_sale_version：按场次和代次唯一的操作记录，按场次和 requestId 去重，包含 ASSEMBLE/RECOVER、状态、发布快照、摘要与操作人。

状态为 UNPREPARED → PREPARING → READY，恢复为 RECOVERING → READY；失败为 FAILED。首次 PREPARING 在独立短事务提交，之后才准备资源；中间错误或进程崩溃不恢复为旧 READY。正常异常记录 FAILED，进程中断可以在 120 秒租约过期后用新的操作编号和当前 expectedVersion 接管。旧操作重放只返回其历史状态，不重新发布。

同一个操作编号只接受相同操作类型、前置版本和操作人；版本冲突拒绝。READY 快照发布后不修改内容；新的装配和恢复使用更大的代次，不能通过重试覆盖旧版本。恢复保留上一已发布版本的规则，不能夹带全局规则修改。

READY 仅表示准备完成，既不把 OFF_SALE 改为 ON_SALE，也不绕过开售时间。推荐先把未来开售的 SKU 设置为 ON_SALE，再完成装配；尚未到时间时仍不能预占。之后正常开停售不需要新装配版本，静态价格、总量、限购或销售时间变化则需要重新装配。

库存行 version 是库存并发控制字段，普通库存每次扣减也会增加；它与装配代次分开。快照绑定静态配置值，不绑定库存行 version 或动态余量，因此单桶连续交易和开停售不会错误使策略失效。

## 装配步骤

1. 检查场次存在、首次纳管发生在开售前且无既有请求，提交 PREPARING 与操作记录。
2. 锁定场次装配记录；按固定顺序等待已有订单操作完成，再锁定 SKU。拒绝 REQUESTED 意图和未完成 RELEASE 消息。
3. 校验价格、库存范围、限购、销售窗口、库存桶完整性及启用规则。仅对全新、未交易、开售前的 SKU 初始化 16 条桶记录，按总量均分，不重建已有桶。
4. 使用现有规则加载器和注册表校验并固化规则，按现有 rule_code 顺序发布；不新增规则工厂或重写规则算法。
5. 以 Redis 装配代次保护发布 SKU 配置和初始库存。普通装配使用 SETNX，不覆盖现存余额；缺失的交易库存或已开售库存必须显式恢复。
6. 校验库存、限购及 HELD 幂等标记、配置完整性，发布不可变场次快照和活动版本指针。
7. 完整性检查成功才提交数据库 READY；任一阶段失败保持拒绝新交易。

重复装配不增加库存。普通缓存刷新对纳管且已开售 SKU 不自动初始化库存，即使尚未发生交易；这条限制不改变未纳管旧场次的兼容行为。

## Redis 代次与 hash slot

沿用库存键：

```
ticket:{skuId}:config
ticket:{skuId}:stock
或 ticket:{skuId}:stock:bucket:0 ... 15
ticket:{skuId}:user:{userId}
ticket:{skuId}:reservation:{reservationId}
ticket:{skuId}:pending
```

新增同槽键及内部字段：

```
ticket:{skuId}:assembly-fence
config 哈希中的 assemblyVersion 字段

ticket:sale:{sessionId}:publication-fence
ticket:sale:{sessionId}:manifest:vN
ticket:sale:{sessionId}:active-version
```

三个新增脚本只用于装配和恢复：sale_assembly_claim.lua、sale_inventory_publish.lua、sale_manifest_publish.lua。原有交易脚本不读取新字段、不改变协议。

数据库锁用于序列化装配；Redis 代次进一步拒绝失去数据库连接的旧工作者。每个 SKU 的恢复整批原子执行，所有旧/新库存、限购、预订和待处理 Key 均声明在 KEYS，使用同一个 {skuId}。场次快照的发布脚本只访问 {sessionId} 同槽 Key，不将场次键与库存键放进同一 Lua。

正常库存脚本仍为单 SKU 原子操作；不同 SKU 之间不声称 Redis 原子发布。由持久化 PREPARING/RECOVERING 门禁挡住跨 SKU 的部分完成状态。旧代次不能覆盖新库存快照或降级 active-version；丢失版本/代次键时新请求拒绝。

## 真实请求接入

TicketController 获取发布快照后，将其中的规则传给已有 RiskDecisionService.admit 重载。订单准入结果内部携带 saleVersion，REVIEW 快照的 policy 内保存 saleAssemblyVersion / saleSessionId。

ReservationIntentService 在创建新意图前检查门禁；ReservationTransactionService 在 Lua 前再校验 READY、缓存摘要、SKU 静态配置和固定版本。MySQL 使用 LOCK IN SHARE MODE，正常请求可并行；维护流程使用排他锁。H2 仅使用更强的测试锁降级，真实并发语义由 MySQL 集成测试验证。

版本切换后旧准入结果在 Lua 前拒绝，不能用 V1 的评分搭配 V2 的库存准备结果。已完成 requestId 仍返回原订单，不再次预占。PASS/REVIEW 共享交易主链，REVIEW 仍只在出票后调查，Agent 与 Python 合同未改变。

这是发布时复制全局规则的静态快照，不是活动动态配置平台。不同场次的快照、活动指针、SKU 库存互相隔离；动态继承、SKU 规则覆盖和热更新留待下一批独立实施。

## 显式恢复协议

只通过管理员 recover 操作触发，不使用原始票数盲目回填：

1. 提交 RECOVERING 新代次，关闭新准入；共享锁保证既有预占事务已经完成或被拒绝。
2. 锁住相关订单及 SKU，拒绝未决意图和未消费释放消息。订单支付/退款在锁上等待，避免重建时账目变化。
3. 对照 HELD 预订、请求指纹、订单、用户购买数、库存桶分配及权威剩余量；不一致时保持 FAILED，不推测补差。
4. 先取得新 Redis 代次，再扫描本 SKU 允许的库存键。将数据库当前余额、用户限购、HELD/RELEASED 指纹标记和原 stockKey/bucketId 作为一个同槽快照原子替换。
5. 验证后发布新的场次版本。已出票订单保持 ISSUED；后续退款仍按原桶回补，重复 RELEASE 不重复加库存。

恢复不修改 MySQL 真实剩余库存，不调整已售数量；桶模式 SKU.remaining_stock 仍只是原服务维护的聚合投影。操作记录包含执行人、编号、代次、摘要及失败原因；没有建立新的库存调整系统。

在线恢复每 SKU 限 10000 条请求记录，扫描限 50000 个 Key，恢复库存 Key 限 30032 个，以限制单次 Lua 执行和内存使用。超过范围保持拒绝，需另行离线维护；不是无限规模恢复方案。恢复仅在当前单节点 Redis 上验证；Redis Cluster 还需要全节点 SCAN 适配及专项验证，不能据同槽脚本推断已完成集群恢复支持。

## 管理 API 与启用

只新增以下管理员端点：

- GET /api/admin/sessions/{sessionId}/assembly：当前持久化状态及 cacheReady。READY 与缓存丢失可以同时出现，此时 cacheReady=false，实际准入拒绝。
- GET /api/admin/sessions/{sessionId}/assembly/versions：最近 100 条操作记录。
- POST /api/admin/sessions/{sessionId}/assembly：装配。
- POST /api/admin/sessions/{sessionId}/assembly/recover：显式恢复。

装配请求示例：

```json
{"requestId":"prepare-20261028-001","expectedVersion":0}
```

恢复时先读取当前 version，再使用新的 requestId，例如：

```json
{"requestId":"recover-20261028-001","expectedVersion":1}
```

失败也消耗代次；不要一直使用旧 expectedVersion 重试。同编号返回历史操作结果并标记 replayed，不代表当前场次仍处于该历史状态；以 GET 当前状态为准。旧操作重放不能把当前代次降回过去。

已有数据库须由部署流程应用 V103/V104，本轮未连接或迁移用户业务数据库。新安装 schema.sql 包含两者。没有假设 Flyway 自动执行，当前项目没有引入新的迁移框架。

Java 直跑：ticketing.sale.armory.enabled=true，16 桶时另设 ticketing.inventory.bucket-count=16。Compose 支持 TICKET_SALE_ARMORY_ENABLED=true 与 TICKETING_INVENTORY_BUCKET_COUNT=16；默认仍关闭、单桶。所有服务实例必须统一配置后再纳管，特别是处理退款和释放的实例也不能混用库存拓扑。

## 观测与验证

沿用 Micrometer / Prometheus，新增 sale_admission / sale_intent_gate / sale_reserve_gate 阶段计时，以及 sale.armory.duration / sale.armory.operation。Grafana 增加装配恢复次数与耗时面板。标签只有固定 operation/phase/outcome，不加入场次、用户或请求编号。抓取时仍不查询数据库。

新增门禁包含数据库与 Redis 往返，不能据此声称提高 QPS。尚未进行本批端到端吞吐对照；应使用这些阶段指标单独测量，而不是把装配成功视为性能验收。

完整命令：

```powershell
# E:\credit_risk\ticket-risk-platform
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'
mvn -o -Pintegration verify '-Dspring.jdbc.getParameterType.ignore=true' '-Dapi.version=1.44' '-Dticketing.test.mysql-image=mysql:8.0.32' '-Dticketing.test.redis-image=redis:7.2.0'
# E:\credit_risk
docker compose --profile observability config --quiet
```

JDK 21 编译 Java 8 target；未声称已验证 Java 8 运行时。测试使用隔离 MySQL 8.0.32、Redis 7.2.0，MQ 关闭并直接驱动真实消费者，不声称完成真实 Broker 压测。Python 服务和契约未修改，本批不重复运行其测试；上一批记录为 35 通过。

## 回滚与遗留约束

按观测部署 → 测试 → 编排接入 → 基础设施源码的顺序回退本批增量。回滚脚本默认预览，并检查所有目标的完成时 SHA-256；有后续修改时拒绝覆盖。上一批 P0/P1 的起点内容原样保留。

一旦场次已纳管，不可在继续售票时简单关闭功能开关或回滚为没有门禁的旧代码。先暂停相关新准入、等待在途事务结束，再安排维护回滚。数据库版本记录和 Redis 代次/幂等标记保留，不 DROP 新表、不重置库存、不 git reset --hard，不对已售数据执行反向回填。

沿用既有“一张订单由一个桶供给”的约束，不新增跨桶拆单；总余量足够仍可能因单桶容量不足而拒绝多张请求。首次纳管拒绝已有请求或已开售场次，不提供在线迁移老交易。大规模恢复、Redis Cluster 全恢复、多节点故障演练、动态策略和 LOW 均不在本次完成声明中。

## 最终实际结果

- 本批开始前重新建立基线：常规 Java 110 项通过，原有 MySQL/Redis 集成 10 项通过，零跳过。
- 最终 mvn verify：常规 Java 111 项通过，真实集成 28 项通过，均为零失败、零错误、零跳过，BUILD SUCCESS。
- 集成明细：原交易兼容 10 项；16 桶装配、并发、故障、规则与 Controller 接入 16 项；单桶连续交易及禁止自动回填 2 项。
- 新增快照单元验证嵌套配置不可变、请求级规则对象隔离及摘要稳定。
- 16 桶测试包含重复装配、两个独立实例并发、发布中途失败、活动隔离、版本切换拒绝旧准入、共享门禁并行、未决意图拒绝、完整缓存丢失后恢复、同桶退款/重复释放、冻结规则、无效规则、真实 REVIEW 快照、待释放消息拒绝、账目不一致拒绝、过期工作者接管、旧代次拒绝，以及正常开停售。
- 单桶测试确认库存 CAS version 增加后装配仍有效、恢复保留当前余量及限购数；尚未卖票但已开售时，普通刷新和普通装配均不能重建丢失库存，显式恢复才允许按数据库账目恢复。
- Docker Compose 在装配开启和 16 桶配置下通过 config --quiet。没有部署新服务、调用管理员接口处理用户真实场次或迁移用户数据库。

测试开发中发现秒级数据库时间字段与毫秒测试数据边界不一致，已对齐测试时间，没有放宽生产销售窗口。独立测试上下文在容器结束时关闭，避免旧连接与定时任务残留。原始基线和最终日志见 .tmp/armory-20261028/baseline.log、final-verify.log，结构化汇总见 verification-summary.json。

回滚命令示例（默认仅预览）：

```powershell
python E:\credit_risk\.tmp\armory-20261028\rollback.py --stage observability
```

确认维护窗口并暂停纳管场次准入后，按 observability → verification → assembly → primitives 顺序使用 --apply。恢复源码后应干净构建再部署，避免旧 target 中遗留新类。所有目标均检查本轮完成时哈希，原有 Git 索引保持不变。原库存 Lua、订单状态机、支付出票消费者与 Python 源码保持原始字节；仅原集成测试增加上下文关闭注解。
