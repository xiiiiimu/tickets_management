# 入口诊断的计时边界

仅在测试启动器启用，不进入生产 JAR，不改认证、业务配置或交易行为。

```powershell
cd E:\credit_risk
.\performance\run-fast-ingress.ps1 -Compile -Diagnostics -Steps '32:1000,128:5000' -Duration 5
# 关闭详细服务端插桩的短对照：
.\performance\run-fast-ingress.ps1 -Steps '128:5000' -Duration 5
python .\performance\analyze_ingress_diagnostics.py <结果目录>
```

客户端每线程持有一个 HTTPConnection，没有共享连接池；并发和客户端队列分别有界。目标时隙不等于已发送请求。计数区分：目标、尝试、request 写完、response 读完、未发送、窗口结束时已写未回与尚在写出、超时。写完只证明数据交给 socket，本轮通过服务端 requestId 匹配确认收到。负载截止后只回收在途响应，不继续发送队列中过期的任务。

客户端按同一 perf_counter 时钟记录：计划时间 → 进入客户端队列 → 被工作线程取出 → JSON 准备 → connect 调用（复用时为零）→ request 写完 → response header → body 读完。连接调用区间包括本机调用与调度，不能当作纯线上 TCP RTT。

服务端按 System.nanoTime 记录：诊断外层 Filter → 内层 Filter → MVC Interceptor → 参数解析/校验 → Controller AOP → MVC 响应与完成回调 → Filter 回程。原先的 before_controller 指标从原测试 startClock Filter 入口计起，**不是 Filter 自身的执行时间**。

Redis 使用真实连接与命令。诊断包装器计时后仍调用原对象；MappedInterceptor 的匹配规则不变。HGETALL / PEXPIRE 的方法时间包含驱动、原生连接懒获取、网络、服务端、唤醒/调度等等待，不是纯 Redis 计算时间。Spring 连接句柄获取计时不能排除命令内的原生连接等待。参数绑定包含请求体读取、Jackson 和校验。Auth 包含 Redis，两者不能重复相加；分析器会校验互斥服务端区间之和。

客户端与服务端时钟原点不作相等假设，只比较各自时长，按 requestId 匹配。客户端 RTT 减服务端可观测区间只是残差，仍包含 Filter 之前接入等待、网络、客户端调度和响应路径，不能全部算作 TCP 或 Filter 时间。客户端排队在发送之前，也不能算到服务端时间。

CPU 使用累计 CPU 时间差分；Java GC 使用 MXBean 累计次数/时间；Tomcat 直接只读采样 executor 与连接计数，不改线程或 JMX 配置。宿主机 CPU/内存与客户端进程内存来自 Windows 只读计数。服务端快照只读内存与管理接口，不查询 MySQL。目标采样间隔 0.5 秒加快照 HTTP 往返，实际间隔以原始时间戳为准。Redis INFO 只在负载窗口外读取；它是整个专属 Redis 实例的统计，包含诊断 GET，不能当 POST 精确调用数。

详细 traces 有 4096 条上限，超出会明确报告遗漏，不拿部分首尾样本计算全部服务端 QPS。CPU 样本、5 秒短测和单次开关对照不能证明稳定容量；不能把历史 276.8ms 按新一次实验的分段数值精确回填。
