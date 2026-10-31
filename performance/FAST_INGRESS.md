# 抢票 HTTP 入口隔离测试

只测生产 `ReservationV2Controller`、Redis 登录身份校验、`@Valid` 参数校验和原 `ReservationIngress` 的有界提交、幂等、限流、拒绝逻辑。

测试启动器位于 `src/test/java/benchmark/FastIngressApplication.java`，不打入生产 JAR。后台仅校验工作线程、读取任务参数、计算校验和并计数；不裁决 PASS，不创建订单，不调用 Armory/风控/Agent，不执行 MySQL 或 Redis 库存预占。生产 Armory 和交易链路没有修改。**结果只能标为入口隔离 QPS。**

运行（PowerShell 7，项目专属 Docker Redis 可用）：

```powershell
cd E:\credit_risk
# 首次只编译测试启动器；不自动执行业务编译或全部回归。
.\performance\run-fast-ingress.ps1 -Compile
# 后续复用已编译启动器：同一 JVM 内逐档测试，每档 5 秒。
.\performance\run-fast-ingress.ps1 -Duration 5 -Steps '16:500,32:1000,64:2000,128:5000'
# 单个短实验：
.\performance\run-fast-ingress.ps1 -Duration 5 -Steps '32:800'
```

客户端每工作线程复用 HTTP 连接，有界客户端队列；不自动重试 POST。单独报告发送端来不及发送的请求、调度滞后、连接数和网络错误，避免把客户端瓶颈当成服务端极限。HTTP 超时 2 秒，独立 Java 进程看门狗默认 180 秒；正常结束/异常都会退出自有 Java，只停止自己启动的专属 Redis。MySQL 不需要运行。

原限制保持：4 个工作线程、256 队列、1000/s 令牌桶（初始突发 1000）、4096 条状态、每用户 4 个在途任务。每轮前仅清理已消费完的**测试临时状态**，不改令牌、容量或业务数据。高发送速率可能触发限流/状态满，所有拒绝原因分开记录；不能把总响应 QPS 当成成功提交 QPS。

测量前先验证未登录、参数错误、身份不一致、重复提交和暂停消费者时队列满。测量时消费者持续运行；每轮短时排空后必须满足 `成功新提交数 == 实际消费数` 且入口队列和活动任务为 0，才算通过。不会长时间等待真实交易排空。

原始结果：`performance/results/fast-ingress-<时间>-<编号>/`，包括配置、逐请求延迟、真实拒绝分类、消费数、线程名、入口阶段计时和清理结果。`before_controller_mean_ms` 包含身份、路由与参数绑定等前置成本，不能单独当作 Redis 命令耗时。

真实后台仍由原 `run-async-ingress.ps1` 保留。本轮不运行它。短客户端也支持 `-Mode real -Port 18086 -Fixture <独立测试fixture.json>` 连接外部已准备好的真实测试服务；fixture 必须标记 `environment=credit-risk-dedicated-test` 并提供足够的独立请求/登录令牌。该模式不会替换消费者、不启动/停止真实服务、不等待后台排空，其 HTTP QPS 同样不能冒充预占 TPS。
