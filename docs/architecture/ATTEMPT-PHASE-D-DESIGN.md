# 阶段D设计：正式画像与短期快速复核分离

仅新可靠attempt路径，独立开关默认关闭，依赖B/C；旧风险、画像生成、Agent、支付出票不改。先审计发现：旧RiskProfileCache.get会吞掉异常变成null；RiskProfileService.resolve会把过期当缺失；旧classifyReservation会采集历史订单/支付聚合。本阶段不改变这些旧接口的业务语义，新增严格读取/快速资格入口。

正式画像：读取现有Redis profile缓存，异常/损坏明确不可用；缺失回源MySQL。即使缓存命中，仍批量读取正式USER/DEVICE/IP画像验证最新版本，防止旧PASS/AGREE掩盖新BLOCK。只有MySQL确认USER不存在才临时REVIEW，不写长期画像；正式记录过期/无效或MySQL异常是可重试校验不可用。有效BLOCK优先于其他画像；有效REVIEW或USER缺失走快复核，USER PASS且无其他有效REVIEW/BLOCK走PASS。

共同校验保留：真实HTTP身份/参数、可靠attempt幂等/用户及队列上限、Armory、既有IP分钟速率策略和USER/DEVICE/IP即时黑名单、最终限购/库存幂等及版本/风险fence。所有用户不扫描长期历史，PASS不执行REVIEW额外规则；REVIEW AGREE仍保留decision=REVIEW供既有出票后调查使用。

可可靠实现的快规则：
- 用户同场次2秒内不同attempt超过3次（默认策略v1，可配置且计入规则指纹；同attempt重复不增加）。
- 同用户同场次另有WAITING/PROCESSING，排除自己和requestId别名；不把跨场次正常并行视作冲突。现有active_owner约束通常已阻止该冲突，本规则是补充校验。
- 现有risk:blacklist:device即时标记；不虚构新的设备历史/指纹。所有人共同黑名单先检查，快规则再检查即时变化。

缓存键：risk:review:{SHA256(sessionId)}:SHA256(userId/deviceId/ip)。身份范围采用真实请求已有字段，不宣称设备指纹。值包含AGREE/REJECT、attemptId、profileVersion（正式画像版本向量摘要）、ruleVersion、context、createdAt/expiresAt及reason。规则指纹包含快规则配置和Armory manifest摘要，安全上下文含设备/IP和装配版本。

TTL默认60秒，并以Redis服务器时间限制到saleEnd、正式画像最早到期时间；不超过销售窗口。AGREE满足全部版本/上下文后可给同场次新attempt复用；REJECT只可复用同attempt结果，新attempt忽略旧REJECT重新判断。过期/规则变化/设备或IP变化/画像版本变化必须重新复核。BLOCK先于缓存。

不同attempt穿透使用同缓存范围SET NX PX短租约，拿不到不等待，不默认放行：返回可重试REVIEW_BUSY。计算后通过Lua比对owner才写结果并释放；崩溃最多等待3秒技术租约，迟到owner不能发布或删除新owner的锁。同attempt已持久裁决直接复用；若裁决提交前崩溃，短期结果可幂等重用。

只有快判断AGREE/REJECT写短期Redis，不更新正式PASS/REVIEW/BLOCK。拒绝当前attempt记录REJECT/REJECTED；画像/缓存/数据库故障记录可重试FAILED码，不伪装成风险BLOCK。缓存失败不默认AGREE。

新增V112仅持久模式字段默认0，不迁移现有数据。启用/回退均需停接纳、排空相关attempt/可靠意图、全节点兼容升级和审核持久开关；不开启开发/生产流量。L1按修改模块及关键边界执行；L2只新建归属校验测试库/空Redis DB；L3仍留到B–E完成。不得以本阶段拒绝率或测试耗时声明吞吐改善。
