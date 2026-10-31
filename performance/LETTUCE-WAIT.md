# Lettuce wait diagnostic (manual, test only)

```powershell
cd E:\credit_risk
.\performance\run-lettuce-wait.ps1
# Once all load processes have exited, analyze the emitted directory:
python .\performance\analyze_lettuce_wait.py .\performance\results\lettuce-wait-<timestamp-id>
```

Prerequisites: existing JDK21, the project's test dependency classpath, compiled fast-ingress fixture, and **already running** `credit-risk-v2-redis` on 26379. No Maven build or production sources are modified. Compilation is limited to `performance/LettuceWaitProbe.java`, with classes written into `.tmp/lettuce-wait-classes`.

A uses only Spring Boot's existing RedisAutoConfiguration with a non-web context, Spring Data Redis 2.6.2, Lettuce 6.1.6.RELEASE and StringRedisTemplate. It reuses the fast fixture's Redis endpoint and environment binding. Effective configuration is saved in connection.json, including native connection sharing, client configuration type, timeout and Netty thread settings. The test config name intentionally matches the existing fast-ingress fixture; its default command timeout is 60 seconds, whereas the main production application.yml specifies 2 seconds. Neither is changed. No component scan, HTTP server, MySQL, risk, stock or payment logic runs in A.

A executes HGETALL, PEXPIRE, and sequential HGETALL+PEXPIRE at 1/8/32/64 workers. Every case warms for 2 seconds, then measures for 5 seconds with no new operations after its deadline; outstanding commands may finish afterwards. QPS includes that short completion tail. A PAIR QPS counts pairs; single-command QPS counts commands. All workers share the existing native connection. Independent test hash has the same three identity fields; expiration remains 30 minutes. It is placed in a claimed empty logical database and removed on exit. Nothing is flushed or copied.

B starts the existing real-auth isolated HTTP fixture only after A exits, records JFR, warms briefly, then runs exactly one 5-second 64-concurrency/700 target requests-per-second case. Its fast consumer genuinely consumes tasks without risk, SQL, stock or payment. A is closed-loop at saturation; B has an externally paced bounded producer. Their command arrival patterns, token distributions (one vs 128), and diagnostics differ, so throughput is not an equivalent capacity comparison. Both use server heaps 128m/384m and SerialGC, unchanged from existing quick fixtures.

Command listener boundaries are **not transport timestamps**: commandStarted marks client dispatch instrumentation; commandSucceeded marks completion-listener execution. They bracket writer scheduling, transport, Redis handling, decode and callback scheduling. A synchronous caller may return before that listener executes; therefore callback_to_return_signed can be negative. Submit-to-listener includes template preparation. There is no direct on-wire write/receive measurement; these sections must not be labeled pure network RTT. Outstanding listener count includes in-flight commands, not just queued ones. Netty pendingTasks is a point sample of EventLoop task backlog. The optional periodic EventLoop heartbeat measures timer lateness including GC/OS scheduling and other loop work, not a unique cause.

JFR uses the JDK profile configuration, with parked/monitor-blocked events above 5ms. Nonblocking NIO network I/O may not emit SocketRead/SocketWrite events. JFR is sampled evidence, not proof that no other block happened. All thread time sums overlap across threads. Analysis filters to recorded measurement windows and excludes warmup/control intervals.

Resource guards stop subsequent cases after three consecutive samples with available host RAM below 256MiB, probe RSS above 768MiB, or probe CPU above 8 core equivalents. Three command failures stop further work. These thresholds are recorded diagnostic guards, not production tuning. The probe has a 240s watchdog, each handshake has a finite deadline, and the HTTP fixture a separate 90s watchdog. Container resource snapshots run concurrently; Redis commandstats/client/CPU/memory/LATENCY/SLOWLOG are captured outside each load window. No Redis configuration, slowlog or latency history is reset. commandstats is instance-wide and excludes network and some queueing. LATENCY can be empty because monitoring is disabled.

Outputs retain raw call start/return/listener times, all percentiles/maxima, per-case host/process/container resources, thread states/stacks, Redis snapshots, connection lifecycle observations, standalone.jfr and http-64/http.jfr. Main production sources are hashed before/after. Cleanup kills only owned test JVMs and deletes only this invocation's exact token/ownership keys. Existing containers and historical fixtures are preserved.
