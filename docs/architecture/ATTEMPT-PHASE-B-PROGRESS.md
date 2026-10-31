# 阶段B实施记录

用户已确认：资格序号→Lua有序、MySQL可并行；后续跨16桶原子分配；只有MySQL确认长期画像不存在才临时REVIEW，数据库异常拒绝。Redis miss必须回源，旧AGREE不得盖过BLOCK。

本轮范围严格限定：可靠attempt登记、5秒WAITING、原子领取/超时、PROCESSING期限解耦、必要背压及相关回归。C/D/E的有序Lua、跨桶、快速复核缓存不在本轮实现。不得将阶段B当作整个整改已完成。

设计接入：新增默认关闭的ticketing.reservations-v2.attempts.enabled；复用tb_reservation_job，以attempt_protocol区分旧2/新3；旧订单RESERVED及支付语义保留。不改现有Lua；新PROCESSING调用原Lua时使用已由Armory校验的开售结束时间，而非旧排队截止。活动销售窗口及技术超时继续有效。

202仅在独立REQUIRES_NEW登记事务提交后返回；该事务保存WAITING、deadline、稳定request/job/reservation/order ID、fingerprint、用户/场次/SKU/设备/IP及版本，不执行画像或库存副作用。worker实际开始后才在数据库领取，避免线程池提交拒绝却提前变成PROCESSING。

阶段C前仍使用现有资格算法，未实现新的PASS/REVIEW语义；该阶段开关不能直接视为最终生产放行条件。部署启用必须先应用增量DDL、核对旧未完成任务、技术超时配置并完成C–F验收。未修改现有开发库/容器数据。

实现位于codex/attempt-lifecycle-phase-b。最终结果、文件清单、事务边界、迁移/回退与测试证据见ATTEMPT-PHASE-B-RESULTS.md。仅在新建owner校验的测试库/Redis DB验证，没有在开发/真实库执行迁移或协议切换。
