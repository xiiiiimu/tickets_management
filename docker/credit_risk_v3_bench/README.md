# 独立 V3 本机测试环境

由 Windows PowerShell 启动，运行资料在 `runs/<随机编号>/`，整个 runs 被 Git 忽略。私密密码、测试 Token、构建副本、新 JAR、应用日志、迁移记录和冒烟证据均按 run 隔离。不要提交其中数据。

本环境的 MySQL、Redis 都是新容器、新卷、新网络，`restart: "no"`；不复用 credit_risk_dev、原 Redis 或其卷。已有开发库与协议不变。应用为本机独立 JVM，不是 Windows 自动启动服务。

结构初始化执行项目 V100、V102–V114 实际 SQL，并记录 SHA-256。V101 的演示库存不加载，只原样加载其中12条规则。结构完成后才设置新测试库协议4及匹配模式位；不是只改协议数字。

使用当前源码副本执行 Maven package，产物是可执行 Boot JAR，不覆盖项目 target 的旧 JAR。独立 JDBC 配置显式启用 S3 参数以及 connectTimeout=5000/socketTimeout=30000。`enableQueryTimeouts=false` 沿用已接受的 S3 技术超时语义。

固定本轮候选端口为 MySQL13316、Redis16389、应用18083，均只绑定127.0.0.1。创建前校验端口；资源名带 run 后缀。连接工具每次检查项目标签、owner标记、固定端口和 schema 前缀。不存在 DROP/TRUNCATE/FLUSHDB/FLUSHALL/删除卷功能，旧测试结果不清空。

为控制本机内存，新测试 MySQL buffer pool 为64MiB、最大连接80，flush=1、sync_binlog=1保持；Redis持久化AOF/everysec开启。应用初始化/冒烟使用Xms128m/Xmx384m，workers16、active_limit16、Hikari30，与云端16并发方案对齐，但不代表已经证明170TPS。所有配置仅作用于这个新环境，完整配置在对应 run 下。

正式数据：5000不同买家，70%PASS/30%REVIEW正式画像，5个SKU各1000张、每SKU原有16桶，单次及累计限购1。Token走原始Redis身份校验；真实Java画像/快速复核、Redis Stream、Lua、可靠意图及HELD事务全部执行。没有快速消费者或伪造任务决策。库存按多SKU准备，不能称作单SKU5000张成绩。

另有10名冒烟买家及独立20张票，不消耗正式5000张。初始3笔REVIEW及额外1笔PASS验证后，冒烟剩余16张。所有场次通过真实管理API在开售前装配READY，不直接伪造Manifest/fence。

仅测锁单：不触发支付、出票、外部Agent。隔离配置关闭MQ/BackgroundJobs，Outbox仍在HELD事务中真实写入；此配置不用于声明支付超时释放等完整履约已验证。实际支付期限仍由原Lua/订单实现设置10分钟。

## 手动管理

```powershell
# 将路径换成实际交付的 run；status只读。stop保留全部卷、数据库和记录。
.\manage.ps1 -Run E:\credit_risk\docker\credit_risk_v3_bench\runs\<run> -Action status
.\manage.ps1 -Run E:\credit_risk\docker\credit_risk_v3_bench\runs\<run> -Action stop
```

重新启动时先手动 scoped Compose start 本run的依赖，再使用 manage.ps1 -Action start；它不会自动迁移、重置或刷新过期业务画像。请勿启动第二个同端口run。

## 后续正式负载（本轮未执行）

```powershell
E:\credit_risk\performance\run-v3-local-k6.ps1 -Run <完整run路径> -Mode ingress -Rate 5000 -VUs 256 -Execute
E:\credit_risk\performance\run-v3-local-k6.ps1 -Run <另一个未用过的run路径> -Mode held -VUs 64 -Execute
```

两种模式都限定30秒、有界VU。入口使用constant-arrival-rate，报告实际POST、202、新提交、429/503、GET和dropped；只有5000个买家，会循环点击，重复/alias不能算新买家或新锁单。HELD模式5000个独立买家迭代，限时内对429/未知保持同一requestId，只有权威终态才允许模拟新点击；GET轮询单列，SQL可见的已提交HELD为准。

正式工具按固定seed42打散买家并轮转分配5个SKU，避免客户端先耗尽一个SKU再串行转向下一个。画像仍为原5000买家的真实种子，不修改其PASS/REVIEW等级；每SKU仍1000个不同买家、1000张库存。配置中保存fixture哈希，便于复现。

一个run只允许一次正式负载，不应顺序执行上述两条。正式runner只有在单独明确授权 -Execute 后，才会另建一个5000张的新测试场次，并提前45秒通过Armory装配，在k6 setup里对准开售点；本次预置的正式场次及其库存不删除、不重置。这样不会把“已开售数分钟后才发压”冒称“开售30秒卖完”。所有SQL采样仅统计该次新场次。已消费数据、过期画像、窗口结束不能自动清空重测，应另建独立run；不能通过反复试跑挑最好值。

负载前要求Windows可用内存至少1GiB；低于384MiB只终止本轮k6，不停止任何既有业务容器。SQL每秒采样会带来观察开销，Prometheus保留前后快照。记录“首个实际POST→5000HELD”与“场次开售→5000HELD”两个起点及30秒内的保守可见下界。SQL采样可见时间是提交完成上界，不是精确commit埋点；临界30秒结果需要解释采样误差，不能把尾部完成算进30秒。

5000rps客户端能力、170HELD/s和5000张30秒目标，均必须由后续真实测量确认，本轮启动和冒烟不证明性能达标。
