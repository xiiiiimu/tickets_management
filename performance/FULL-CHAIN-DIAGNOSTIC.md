# 全链路一次综合诊断：复现与数据说明

已完成的唯一业务批次：`E:\credit_risk\performance\results\full-chain-20261029_231023_4e66`。

交付入口：FULL-CHAIN-AUDIT.md、FULL-CHAIN-TIMELINE.md、BOTTLENECK-RANKING.md、THROUGHPUT-FUNNEL.md、OPTIMIZATION-ROADMAP.md。可视化为上述结果目录的timeline.html（独立文件，悬停查看区间）。所有后续分析均为离线，不发送业务请求。

## 环境及保证

使用项目现有JDK21.0.11、target/classes和benchmark-classpath.txt内实际依赖。没有Maven依赖升级或生产构建改造。测试源在performance外置目录，只有显式独立main/javaagent启动会加载，不进入生产正常启动。

仅连接已运行且Compose标签匹配的credit-risk-dev-mysql和credit-risk-v2-redis，不启动/停止/重建容器；新建owner专属MySQL库/账号、原子领取空Redis逻辑库，不使用开发数据。端口18090必须空闲。真实profile固定入口4/256、sale4/queue0、Hikari默认10、POST1000/s、持久pending512、active4、queue deadline30秒。JVM Xms128m/Xmx384m/SerialGC/UTC不变，额外启动JFR。测试专用安全切断付费Agent/MQ/支付/出票，保留真实规则和库存/订单/Outbox写入。

唯一批次50有效用户、独立活动/场次/SKU、1000库存/16桶、最大发送并发10，不使用快速消费者，不发抢票预热。初始化需要在首次开售前；修正后的测试夹具在Token/用户准备后预留30秒装配窗口，这不是生产限流/队列/业务期限调整。4个请求使用原requestId/body/token/sourceIP重放。

## 未来手动完整复现（本轮不再执行）

先核对当前生产代码版本和哈希，不把本轮观测结果套到之后开发的代码。确保现有专属服务已由用户手动运行，target/classes已正常编译且与源匹配。脚本不会自动启动服务，也不恢复上一轮已回退候选。

```powershell
cd E:\credit_risk
.\performance\run-full-chain-diagnostic.ps1
```

脚本将输出RESULT_DIRECTORY，仅一个50/10循环，不会自适应加档或重试HTTP。若失败，先看目录，不能自动重复已经发出的请求。启动限时70秒、业务终态观察限时60秒、拥有进程watchdog300秒；仅成功核对后自动按owner清理自己的库/账号/Redis键，失败保留以便核验。程序总运行时间还包括编译、装配等待和只读元数据采集，不等于发送窗口或批次完成时间。

## 离线复现已完成结果

```powershell
cd E:\credit_risk
$result='E:\credit_risk\performance\results\full-chain-20261029_231023_4e66'
python .\performance\analyze_full_chain.py $result

$jdk='C:\Program Files\Java\jdk-21.0.11\bin'
$cp=(Join-Path $PWD 'ticket-risk-platform\target\classes')+';'+(Get-Content -Raw .\ticket-risk-platform\target\benchmark-classpath.txt).Trim()
& "$jdk\javac.exe" -encoding UTF-8 -proc:none -cp $cp -d .tmp\fullchain-classes .\performance\FullChainJfrSummary.java
& "$jdk\java.exe" -Xmx192m -cp ($cp+';'+(Join-Path $PWD '.tmp\fullchain-classes')) FullChainJfrSummary $result
python .\performance\write_full_chain_reports.py $result
```

流式读取JFR，不建议把83MB的jfr-events.json完整载入Python内存。该展开JSON也已保留，但full-chain.jfr才是原始记录。

## 原始数据地图

- version.json：HEAD、脏工作区说明、176生产源哈希验证、运行工具SHA、单冷批次标记。
- configuration.json：精确JVM/启动参数、隔离库/Redis DB编号；不含真实密码。临时账号密码只在忽略目录内，账号已删除。
- 50-raw.json / final-probe.json：逐请求统一traceId=requestId、原始nano起止、HTTP尝试、SQL/Redis/事务span、全部成功与空claim、调度轮次、100ms线程状态/栈/CPU/池快照。生产order/intent的trace字段仍沿用原jobId，没有修改业务字段。
- 50-result.json / 50-idempotency.json：50唯一请求的HTTP/库存/订单终态、4次幂等重放。summary.json不混入其他批次。
- request-timeline.csv/json、thread-task-timeline.json、pending-with-spare-sale.json、throughput-funnel.json：从原始数据计算的明细和每秒计数。
- mysql-lock-wait-samples.json：500ms只读采样，混合kind=lock与kind=connection；不能把每行或关系分组当独立等待次数。mysql-lock-episodes.json保存关系分组和观测跨度，不是精确锁时长。
- mysql-before/after.json、mysql-digest-delta.json、explain.json：P_S启用状态、语句digest、全局fsync/IO、索引和小数据EXPLAIN。元数据窗口比50请求窗口宽；全局计数共享实例，不能归因所有IO于本批。
- post-run-readonly-config.json：测试结束后只读核对持久化参数及容器资源限制，不是测试时资源利用率。
- full-chain.jfr / jfr-analysis.json：原始JFR及按本批时钟校准窗口提取的流式摘要。
- resource-summary.json：宿主/Java CPU、内存、GC、池采样；instrumentation-limitations.json记录缺失项。
- cleanup.json：本轮Java退出、专属数据清理、Docker生命周期操作0；初次失败预检查的0请求证明在另一个`full-chain-20261029_230605_a978/cleanup.json`。

## 不能据此声称的结论

没有稳定容量数据；当前1.69TPS不等于硬上限。没有独立压测机，也没有无探针对照，不能量化诊断开销。JDBC连接ID提取未适配驱动，Docker stats采集为空，不能关联每笔InnoDB等待或证明容器CPU/IO饱和；没有为补数据再发一批。P_S waits history未开启，未改配置。JFR空闲park不是CPU忙，Java同步SQL时长不是纯服务端执行，更不是纯锁等待；包含关系的P95不相加。全部PASS，未测REVIEW调度延迟或BLOCK失败分支。

本轮测试后已停止自有进程并清理自有数据，现有Docker容器状态未改，生产逻辑未改；后续优化需另行确认。
