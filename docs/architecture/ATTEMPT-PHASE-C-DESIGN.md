# 阶段C：资格发布有序，Lua有序，MySQL确认并行

本阶段承接B，不实现D的长期画像回源/REVIEW快复核缓存，也不实现E的跨桶quantity。现有完整风控暂时保留；同SKU的PASS/REVIEW进入同一资格发布通道，不按decision重新排序。原有旧协议claimBatch保留；没有200ms资格屏障可删除。

## 源码核对与差异

B的新attempt在sale worker实际启动后领取，PROCESSING中完成风险判断、prepare和reserve。它没有PASS绝对优先，但多个worker可无序竞争reserveRuntime。原reserve将Lua和订单确认放在同一事务，不能通过在该事务外加一把SKU锁实现用户选择的并行确认。

现有reserve_ticket_bucket.lua通过fingerprint/reservation marker幂等；abort_reservation_v2.lua会为没有库存记录的请求创建ABORTED墓碑，迟到reserve返回-5。原恢复器以job→intent顺序锁定，未完成意图可重试/补偿。这些是本方案处理跨系统提交间隙的基础，不修改Lua及其库存协议。

## 线性化与事务

```
HTTP：原可靠WAITING登记提交 → 202（保持B）
worker：原子PROCESSING领取 → Armory/risk → durable admission
  → prepare事务：Armory意图Gate → job锁 → guard准备 → REQUESTED意图
      → 同SKU/装配版本lane锁 → 分配qualification_seq → COMMIT
  → Lua短事务：Armory Gate → job锁 → intent锁 → 动态risk fence
      → 同SKU/版本lane锁 → 仅next_lua_seq可执行原Lua
      → lua_turn_finished=1，next_lua_seq++ → COMMIT
  → 确认事务：原最终Armory Gate → job/intent/risk fence
      → 读取校验既有HELD marker（不再次执行reserve Lua）
      → 原16桶CAS、累计限购、预占、订单、风险记录、Outbox → COMMIT
```

“资格就绪顺序”明确线性化为可靠意图事务末尾的序号发布/提交顺序，不声称按不同机器的墙上时钟精确排序。两个任务同时就绪，由同SKU lane锁裁定；先发布的资格不能被后来任务通过线程竞争越过。发布时间不是HTTP到达或claim顺序。

按(sku_id,sale_version)隔离，绝不使用dispatch id=1实现资格排序。既有id=1仍仅保护B的容量512和活动上限4，没有删掉。不同SKU的lane彼此独立。MySQL确认不持有lane锁；下一笔需要等待前一笔**Lua短事务的元数据提交**，不等待其完整订单确认提交。

这会产生每笔额外短事务提交和一次确认时的marker读取；同SKU仍受Lua+短事务提交的串行成本约束，不宣称吞吐增加。严格顺序必然有队首阻塞：死worker最多需等待既有租约过期/恢复，无法安全跳过未知库存结果。未测吞吐成本，默认关闭；L3统一比较，不能偷偷改为无序或串行完整确认。

## 故障、重试与迟到

- 序号分配和REQUESTED意图同事务：回滚不消耗序号，没有“已分配却无可恢复任务”的空洞。
- 后序抢到worker但未轮到Lua：抛Pending、回滚短事务、条件让出租约并归还线程；保留PROCESSING/admission/序号，不重新风控，不回WAITING、不重算5秒、不sleep等待同批任务。
- lease重领更换token，job/intent锁和token校验阻止旧worker确认或覆盖新owner。
- Lua成功、短事务未提交：可靠意图与当前cursor仍在；再次执行使用原marker幂等，也可确认ABORTED墓碑后原子推进。不能在未知Redis结果时仅更新数据库跳号。
- 短事务提交、确认未完成：lua_turn_finished已持久化；恢复后读取原HELD marker及原expiresAt，不再次扣库存，不重新开始10分钟倒计时。
- Lua失败/订单确认失败：原abort确认释放/墓碑，再将未完成的当前turn及任务终态同事务提交。已完成turn不重复推进。
- 非队首失败不能直接推进cursor；先前task未解决时保持可恢复状态，不把后序失败记录当作跳过前序的依据。
- 原独立恢复器在确认abort之后、job终态之前也调用同一推进协议。补偿/提交失败保持REQUESTED和PROCESSING等待恢复。
- Redis、MySQL不可用/Armory版本变化维持安全拒绝、技术超时和既有恢复机制；不重建缺失库存，不关闭Armory。

## 共享状态与上线边界

V111仅增加dispatch.ordered_lua_enabled（默认0）、job.qualification_seq、job.lua_turn_finished、唯一索引以及tb_reservation_sku_order。fresh schema同步；既有schema不应重复运行fresh schema代替迁移。

独立配置`ticketing.reservations-v2.attempts.ordered-lua=false`默认关闭。开启需B启用、V111应用且显式审核将持久ordered_lua_enabled切1。兼容节点启动检查开关和持久模式一致；需要全节点升级、停接纳、排空旧attempt/意图的发布流程。不能依赖启动校验防护仍运行的旧二进制。

没有在现有库执行迁移、激活模式或改写库存。回退必须先停接纳并排空已发布资格/未完成意图，核对库存与订单，再审核模式切0、关开关。保留附加表/列即可，不删除业务数据。不得直接切换有未完成lane的运行中节点。

## 测试分层

L1只编译直接受影响模块，针对顺序、跨SKU、确认并行、失败墓碑、回滚、重领、迟到、模式和让出worker测试。L2使用专属临时MySQL schema/账号和空Redis DB验证真实Lua及事务分离。L3全量、真实双JVM中断、持续负载及AB统一留到B–E完成，不在C宣称性能提升。

本阶段不修改LLM、Python Agent、Harness、支付/出票状态机、现有Lua、线程数、容量、连接池、限流或容器配置。
