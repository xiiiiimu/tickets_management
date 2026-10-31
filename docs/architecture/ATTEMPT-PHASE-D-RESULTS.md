# 阶段D：正式画像安全回源与REVIEW快速复核

本阶段实现D，默认关闭，未迁移/激活现有开发库。未修改LLM、Python Agent、Harness、凌晨画像生成、支付出票、Armory、库存Lua或16桶协议；未进入E跨桶改造，未运行压测。

设计见[ATTEMPT-PHASE-D-DESIGN.md](ATTEMPT-PHASE-D-DESIGN.md)。基线保存在`.tmp/attempt-phase-d-baseline-20261030_103616`，保护进入D前包括B/C在内的全部未提交源码；没有reset/clean/stash或自动提交。

## 新路径及正式画像边界

```
HTTP：原身份/参数/限流 → 可靠WAITING登记提交 → 202（保持B）
sale worker：原子领取PROCESSING → Armory当前Manifest
  → 严格Redis画像读取 + MySQL批量核对USER/DEVICE/IP正式画像
  → BLOCK拒绝；正式画像过期/异常/不可确认则校验不可用
  → PASS/REVIEW共同的IP分钟策略及即时黑名单校验
  → PASS直接取得资格；REVIEW读取短期结果，未命中执行快速复核
  → 持久裁决 → 原C资格序号发布 → 有序Lua → 并行MySQL确认
```

Redis未命中回源MySQL；只有MySQL确认USER画像不存在才临时REVIEW。不会写长期PASS或自动生成画像。USER有正式PASS且其他有效画像无REVIEW/BLOCK才直接PASS；任一有效BLOCK拒绝。参与判断的正式画像已过期不是“新用户”，返回不可用，不自动降级。

即使Redis画像命中仍批量查询MySQL，确保旧PASS/旧AGREE不覆盖最新正式BLOCK。Redis读取/解析异常也明确不可用，不沿用旧缓存层“异常等于miss”的行为。现有RiskProfileService/Cache/Repository写入逻辑均未修改。

新路径不调用RiskFeatureService.collect，不查询长期订单、支付历史或跨场次行为。共同IP分钟阈值仍来自已装配DECISION_POLICY，以原有数值比较保留，不截断小数；HTTP入口1000/s等限流配置未调整。

## REVIEW结果及缓存

- 结果只有AGREE/REJECT；长期decision仍为PASS/REVIEW/BLOCK。REVIEW+AGREE的持久admission仍为REVIEW，沿用已有出票后调查触发条件。
- AGREE可由同用户同场次、相同设备/IP及有效上下文的新attempt复用，仍执行共同校验、幂等、限购、库存和最终风险fence。
- REJECT只拒绝当前attempt；同attempt可幂等复用，新attempt忽略旧REJECT并重新快速复核。不写永久黑名单或惩罚窗口。
- 规则v1检查：同用户同场次2秒内超过3个不同attempt；同用户同场次其他WAITING/PROCESSING；现有即时设备黑名单。并发attempt查询显式限定attempt_protocol=3，匹配既有idx_attempt_user的协议/用户/状态前缀；不新增数据库索引或扫描历史订单。
- 不把不同场次的正常并发视作冲突，不声称已有可靠的多设备登录会话索引或硬件设备指纹。deviceId仅是项目既有请求字段。

缓存键：`risk:review:{SHA256(sessionId)}:SHA256(userId|deviceId|ip)`；去重租约为同键`:lock`，同Redis hash slot。

值包含decision、reason、attemptId、profileVersion（正式画像版本向量SHA256）、ruleVersion、context、createdAt和expiresAt。ruleVersion包含快规则配置、实现版本及已发布Manifest摘要；未发布到Armory的全局规则编辑不等于该场次有效规则版本变化。

TTL默认60秒，以Redis服务器TIME限制到对应SKU saleEnd及正式画像最早到期时间。规则/装配版本、画像版本或必要身份上下文变化后旧AGREE不匹配；过期后重新复核。不同SKU可复用同场次缓存，但仍检查当前SKU窗口和自身限购。

不同attempt并发穿透用SET NX PX的3秒owner租约。竞争失败立即REVIEW_BUSY，不占线程等待、不默认放行。发布结果与释放通过Lua校验owner原子完成；旧owner不能写入或删除新owner的锁，进程故障靠有界TTL恢复。

缓存TTL不是PROCESSING业务截止。已提交admission的同attempt恢复仍幂等复用，最终最新BLOCK由既有事务fence阻止；不增加固定10秒/30秒处理期限。

## 状态与查询

快复核REJECT及正式BLOCK：当前attempt为REJECTED，公开RISK_REJECTED，不占库存、不改长期画像。

画像/缓存/信号不可用、去重忙等：若数据库可写，当前attempt为FAILED，并公开validationUnavailable、retryable、retryWithNewAttempt；必须主动新点击建立新attempt，原requestId保持原终态。不会把技术不可用伪装成风险BLOCK。

这些是**202之后的后台结果**，不声称已经返回的POST会改成503。若登记前数据库失败，仍沿用B不返回可靠202；若整个数据库故障导致后台无法记录失败，则保留原PROCESSING/租约等待恢复，不能伪造终态。

快复核发布上下文失效使用REVIEW_CONTEXT_UNAVAILABLE，避免触发原abort对包含EXPIRED的错误码进行旧期限分类。

## 修改文件及迁移

Java项目下：

|文件|作用|
|---|---|
|risk/AttemptRiskProfiles.java（新增）|严格缓存读取、正式画像批量核对、缺失/过期/异常区分及版本向量，只读不回写|
|risk/ReviewQuickCheck.java（新增）|共同实时信号、三项快速规则、缓存校验、owner租约及原子发布Lua|
|risk/AttemptRiskAdmission.java（新增）|按长期等级分流，形成兼容既有持久admission；保留原画像负分，不改Agent裁决|
|ticket/ReservationV2Service.java|仅新开关下改用快资格入口，持久裁决恢复不重执行；不可用明确记录失败码|
|ticket/ReservationAttemptStore.java|D开关依赖B/C及持久模式一致性校验|
|ticket/ReservationDispatch.java|区分风险拒绝与可重试校验失败的公开查询结果|
|resources/application-reservations-v2.yml|quick-review默认false及新策略参数，不改变池/队列/限流|
|resources/schema.sql|fresh schema新增默认关闭的quick_review_enabled|
|resources/db/migration/V112__attempt_quick_review_mode.sql（新增）|已有库附加模式字段，默认0，不改旧数据|
|risk/AttemptRiskProfilesTest.java（新增10项）|缺失/异常/过期/BLOCK/版本/负分只读边界|
|risk/ReviewQuickCheckTest.java（新增17项）|AGREE/REJECT语义、失效、上下文、频率、并发owner、缓存/SQL失败、阈值及查询范围|
|risk/AttemptRiskAdmissionTest.java（新增7项）|分流、共同校验、未知安全拒绝及原阈值兼容|
|ticket/AttemptQuickAdmissionEntryTest.java（新增5项）|新/旧开关分支、恢复幂等、技术失败不进入库存|
|ticket/AttemptQuickModeTest.java（新增4项）|迁移默认值、依赖、模式一致性及公开状态|
|integration/QuickReviewAttemptIT.java（新增4项）|真实Redis/鉴权/Armory/MySQL、限购、BLOCK、REJECT重试及缓存Lua|
|ticket/AttemptQuickMysqlTest.java（新增2项）|实际V112增量迁移/模式和并发裁决唯一持久化|

另新增`performance/quick_review_integration.py`用于归属校验的独立正确性测试编排，未修改已有Harness或性能工具。

V112尚未在现有开发库执行。启用须V110/V111/V112兼容代码全节点升级，停接纳排空旧attempt/意图，审核持久runtime_protocol=3、ordered_lua_enabled=1、quick_review_enabled=1，再开启对应配置。不能在线混跑新旧风险语义，启动检查不能阻止仍运行的旧二进制。

回退：停接纳、排空/安全恢复相关attempt及意图、核对库存订单，再审核quick_review_enabled=0并关闭quick-review。可保留附加列，不需DROP。短期缓存无需删除，会自然到期；严禁清理整个Redis或业务数据。C/B的回退各自仍需原协议排空步骤。

## 测试结果及实际耗时

**49个不同用例最终全部通过：L1 43项，L2 6项。** 累计61项次包含定向复验及首次1项测试设置错误，不能当成61个不同测试。没有重跑B的112项或C全套。

|执行|范围|实际耗时|
|---|---|---|
|L1首轮|36项；35通过、1项Mock重复设桩触发旧异常|主类编译12.64秒、测试编译12.58秒、命令22.64秒|
|L1修复|只重跑该裁决测试类6项，全部通过|测试编译11.17秒、命令15.36秒；JUnit 1.884秒|
|L1公开状态|仅新增1项|编译8.48/7.8秒、命令18.30秒；JUnit 3.224秒|
|L1默认关闭兼容|仅新增1项，同时编译MySQL测试|编译15.84秒、命令26.16秒；JUnit 2.206秒|
|L1上下文/owner分类|3项定向验证|编译12.58/12.09秒、命令26.17秒；JUnit 6.326秒|
|L1原数值阈值兼容|4项定向验证|编译17.44/17.75秒、命令31.50秒；JUnit 5.844秒|
|L1正式负分只读|2项定向验证|编译13.11/9.38秒、命令17.56秒；JUnit 2.618秒|
|L1有界查询与并发去重|2项定向验证|编译10.23/9.86秒、命令19.38秒；JUnit 4.658秒|
|L2真实库存/缓存链|4项|JUnit suite 88.36秒；Maven约104秒；schema初始化14.047秒|
|L2 MySQL迁移/并发裁决|2项|JUnit suite 14.559秒；命令耗时见mysql/tests.log|

首轮失败是测试在已抛异常的Mock上使用when重新设桩；改为doReturn后完整重跑该6项，断言保留，没有跳过失败用例。首次日志也保留。

真实库存集成3个场次沿用既有约15秒开售等待，方法分别18.513/18.375/17.765秒；另一个Redis脚本方法2.389秒。suite还含Spring启动。耗时主要来自夹具开售窗口及JVM/Spring/Maven/编译启动，不是单笔锁单性能或业务等待P95。没有提高超时门槛。

并发测试采用闩锁、屏障、故障注入或显式过期命令；没有用sleep构造领取/去重竞态。真实身份/Controller使用MockMvc和真实Redis Token，并非网络HTTP发压。

真实库存组最终核对：3个SKU MySQL/Redis总库存分别100/99/99，合计298；2个可靠订单；所有测试任务终态、lease均清空。拒绝、MySQL限购失败的库存恢复正确。测试没有调用Agent、支付或出票。

最终几项兼容性/错误码/查询范围收尾仅重跑直接相关L1，没有为它们重跑整套真实库存集成；本阶段数据不是一次冻结版本全量验收。实际V112和并发裁决L2已完成。L3仍按约定留到B–E完成。

## 原始结果与复现

- `performance/results/attempt-phase-d-20261030_105344`：真实库存4项、全部L1日志/脚本/选择器、源码身份、仅D补丁及报告。
- `performance/results/attempt-phase-d-20261030_105936`：独立MySQL迁移/并发2项。
- cleanup.json均确认专属库/账号不存在、所启动进程退出；Redis组剩余键0。没有Docker生命周期操作或性能负载。

当前工作区已编译情况下：

```powershell
E:\maven\apache-maven-3.9.12\bin\mvn.cmd -o -f E:\credit_risk\ticket-risk-platform\pom.xml '-Dtest=AttemptRiskProfilesTest,ReviewQuickCheckTest,AttemptRiskAdmissionTest,AttemptQuickAdmissionEntryTest,AttemptQuickModeTest' '-DargLine=-Xmx256m -Duser.timezone=UTC' surefire:test
python E:\credit_risk\performance\quick_review_integration.py
python E:\credit_risk\performance\quick_review_integration.py --mysql
```

测试脚本依赖当前工作区的已有JDK/Maven、依赖classpath和阶段B保存的Maven启动命令，专属服务需预先运行；脚本不启动容器。受影响编译脚本及精确选择器保存在结果l1目录。

## 尚未验证及后续

- 未执行L3全量、双JVM故障恢复、持续负载、网络故障或AB；本阶段不提供吞吐改善百分比。
- 即使AGREE命中仍有MySQL画像版本核对及共同校验；缓存不消除全部数据库/Redis成本。若要减少该SQL，需要另行设计可靠版本失效协议，不能依赖旧缓存放行。
- 当前快速复核默认参数为第一版可审阅策略，不等于经过负载校准；同场次并发冲突规则只是补充，真正幂等/唯一活动owner仍由B持久约束保证。
- 新REVIEW admission没有历史订单聚合特征，不伪造历史值；既有事后调查触发、写入和状态机未改，但调查对缺失历史上下文的质量/降级还需最终端到端验收，本轮未调用外部付费服务。
- 用户设备字段不等于可信硬件身份；未实现不存在的会话索引或设备历史风险标记。
- E跨16桶quantity、售罄/恢复升级尚未实现；本阶段保持原库存协议，不能宣称分桶碎片已解决。

本轮停在D完成，不自动执行E或性能实验。
