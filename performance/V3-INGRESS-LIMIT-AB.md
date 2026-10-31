# V3 入口限流 A/B（2026-10-31，本轮已提前结束）

## 0. 测试限制（先读）

- **单机测试**：wrk2、nginx、应用 JVM、MySQL、Redis 全部跑在同一台 2 vCPU 主机上，没有独立压测机。wrk2 约占 0.05 核，nginx 约占 0.03～0.05 核。**结果只用于比较不同配置，不作为最终容量结论。**
- 所有配置使用相同的业务 Worker 设置：active_limit=16、workers=16、连接池 30、到达顺序通道开启、V3 开启、Xmx2g。
- **负载**：wrk2 开放模型，**600 rps 恒定速率**，300 个连接，3 万个用户，每个请求的 requestId 都不同。
- **时长**：先以相同速率预热 60 秒，再正式测 60 秒；统计窗口是第 5 秒到结束前 1 秒。
- **完成情况**：
  - 计划 21 次运行，**完成 19 次**。n150 和 n200 的第 3 轮未完成（n150-r3 在压测中途被终止，数据已丢弃）。
  - 追加的 n60、n80 和中等负载对照（ref100）**均未运行**。
- **不变量**：每次运行结束都校验"订单数 = RESERVED 数"，全部通过；各状态码之外没有其他错误。

## 1. 结果（均值 [最小–最大]）

| 配置 | 轮数 | 到达应用的 HTTP QPS | **HELD TPS** | **202 后超时率** | Java 核数 | mysqld 核数 | MySQL QPS | 客户端延迟 p50 / p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| 无入口限流 | 3 | 593 | 7.2 [5.9–9.6] | 31.3% [28.6–34.8] | 1.21 | 0.51 | 4930 | 923 ms / 3.08 s |
| Spring post-rate 100 | 3 | 600 | 31.4 [30.1–32.1] | 7.7% [5.5–9.6] | 1.10 | 0.61 | 3740 | 26 / 125 ms |
| Spring post-rate 150 | 3 | 600 | 30.5 [29.4–32.1] | 14.5% [12.2–17.6] | 1.13 | 0.60 | 4050 | 16 / 109 ms |
| Spring post-rate 200 | 3 | 600 | 28.9 [23.6–39.1] | 14.6% [4.9–22.6] | 1.12 | 0.61 | 4240 | 23 / 168 ms |
| **nginx 100 r/s** | **3** | **100** | **40.2 [39.3–41.5]** | **2.75% [1.3–4.3]** | **0.99** | 0.70 | 4540 | 9 / 138 ms |
| nginx 150 r/s | 2（未完成） | 150 | 32.3 [31.5–33.0] | 8.0% | 1.03 | 0.64 | 4170 | 3 / 119 ms |
| nginx 200 r/s | 2（未完成） | 200 | 29.4 [28.7–30.1] | 9.6% | 1.07 | 0.62 | 4260 | 8 / 109 ms |

- 客户端实际 QPS 在各配置下都约为 590～596。
- Spring 限流时，所有请求仍会进入应用，所以应用侧 HTTP QPS 仍是 600。
- 参照：上一轮中等负载约 41 HELD/s。**只有 nginx 100 r/s 达到了这个水平。**

**逐秒 HELD 时间线**：每 10 秒取平均，每个配置每轮各一行。完整的逐秒数据在 `results/lim-summary.json` 和各次运行的 `capacity.json` 中。

```
none  [5.5 6.1 6.5 6.1 6.6 6.5] [4.3 6.2 5.8 6.4 6.4 6.8] [6.9 9.4 8.6 10.2 13.1 10.8]
s100  [25.3 34.5 30.9 33.3 33.9 29.5] [21.6 27.7 34.5 31.1 29.8 31.8] [26.6 31.3 26.7 34.4 33.5 35.2]
s150  [21.1 27.8 33.5 33.4 34.3 39.2] [23.5 28.7 31.4 31.2 28.3 34.4] [13.4 26.8 30.6 31.6 29.5 34.5]
s200  [25.1 35.1 42.6 43.2 40.9 38.5] [15.7 24.6 23.9 27.5 24.5 24.5] [17.6 22.1 26.1 27.1 23.7 24.6]
n100  [32.7 38.7 40.5 41.9 39.3 38.6] [30.9 37.8 41.6 41.7 41.2 38.5] [30.3 42.4 42.0 40.1 45.1 39.2]
n150  [24.1 36.1 32.0 32.5 27.8 29.8] [23.9 33.5 31.1 34.9 35.5 37.4]
n200  [19.5 31.6 29.8 27.4 31.6 34.8] [27.0 30.2 26.3 26.9 30.8 31.7]
```

nginx 100 的三轮在 10 秒之后稳定在 38～45/s，持续吞吐稳定。s200 的三轮之间差异很大，第 1 轮约 40，第 2、3 轮约 24。

## 2. 为什么 nginx 100 最好

**被拒请求在各条路径上的开销（审计结果）：**

| 路径 | Java 逻辑 | Redis | MySQL |
|---|---|---|---|
| nginx 429 | 无，请求不进入 JVM | 0 | 0 |
| Spring post-rate 429 | Tomcat、TraceFilter、指标过滤器、RefreshTokenInterceptor、LoginInterceptor、Jackson 反序列化、@Valid、Controller、`posts.take()`、带堆栈的异常、@ExceptionHandler | 2 条（HGETALL login token、EXPIRE） | 0 |
| 容量已满的 BUSY 429 | 以上全部，再加 fingerprint（JSON+MD5）、`runtimePlan`、3 次 Db.id | 2 + MGET + EXISTS（18 个 key）+ GET fence + HGETALL config + accept Lua | **1 个 REQUIRES_NEW 事务**（JOIN 加 LOCK IN SHARE MODE，约 6 条服务端语句） |
| 受理成功 | 同上，再写入 Stream | 同上，Lua 内部执行 XADD | 同上 |

- Spring 限流把 MySQL 那段挡住了，但每秒 500 个被拒请求仍要走完整的鉴权、JSON 和异常链路，额外消耗约 0.1～0.2 核。
- 阈值放到 150 或 200 时，会有更多请求越过限流，走到 `runtimePlan` 事务后再拿到 BUSY。这部分开销又和 Worker 抢同一块 CPU，所以 HELD 反而更低。
- **阈值越接近"HELD 能力 × 约 2.5"越好**。这台机器的 HELD 能力约 40/s，对应约 100。

## 3. 语义检查（只读核对）

- **被限流的请求不会后来又生成订单**：
  - nginx 429：请求没有进入应用。
  - Spring 429：在 accept Lua 之前就抛出了异常。
  - Lua BUSY：只清理已过期的 ZSET 成员，没有写入任何请求状态（见 v3_accept.lua 第 6 行和第 29 行）。
- **结果查询不受 POST 限流影响**：
  - nginx 的 map 只对 POST 生成限流 key；GET 的 key 为空，不计数。
  - Spring 侧 GET 走独立的 `queries` 桶（query-rate=200）和 readers=2 的信号量。注意：这两个上限满了会返回 STATUS_BUSY，属于非终态，不会显示为失败。
- **前端**：
  - 发 POST 之前先 `remember(follow)` 保存 requestId，429 时提示 nginx 返回的 errorMsg，原请求编号保留，可以重试。
  - 轮询时 UNKNOWN 不是终态，不会显示"抢票失败"。
  - **发现的问题**：被拒的请求之后仍会每秒查询一次 by-request，最长 120 秒。洪峰时，被拒的 POST 会变成一大批不受 nginx 限制的 GET。本轮的 wrk 没有模拟这些 GET，所以真实浏览器环境的结果会比本轮差。修改建议见第 7 节第 5 项。

## 4. 这一小时花在哪里

| 步骤 | 每次运行耗时 | 占比 |
|---|---:|---:|
| JVM 启动、Spring 上下文、MySQL 重建表、生成 3 万用户和 token 的测试数据 | 约 93 s | 约 42% |
| 预热（按要求 ≥60 s） | 约 64 s | 约 29% |
| 正式压测 | 约 63 s | 约 28% |
| 队列排空、写结果 | 约 3 s | 约 1% |

- 19 次运行，每次约 3 分 40 秒，合计约 70 分钟。**没有出现进程卡住或队列无法排空的情况。**
- **超时保护齐全**：
  - 每次运行都包在 `timeout 1500` 里；
  - wrk2 用 `-d60s` 限定时长，单请求超时 10 秒；
  - 排空等待最长 60 秒；
  - 每次运行结束时 PROCESSING 都清零。
- 用时偏长的原因是**计划本身太大**：21 次运行约 77 分钟。我又追加了 9 次（n60、n80、ref100），并且一直轮询等待，这是我的安排问题。
- 以后可以这样缩短：同一个 JVM 内连续跑多个配置，只重建场次，不重启 JVM、不重新生成测试数据，每次运行能省下约 40% 的时间。

## 5. 进程与数据

- 已终止 lim-all.sh、lim-ext.sh、caprun/cap-v3、测试 JVM、wrk2 和实验用 nginx。
- MySQL（13306）和 Redis（6390）照常运行，没有删除任何数据。
- 本环境没有 RocketMQ 容器。

## 6. 修改的文件

- **生产代码：本轮没有改动**，对现有 V3 业务逻辑没有影响。V3 第 1 轮的代码仍是之前交付的版本。
- **压测工具（不属于生产代码，只在会话工作区里）**：
  - `CapacityExperimentIT.java`：新增 flood 模式、wrk2 恒定速率、nginx 启停、nginx CPU 和 MySQL Questions 采样；
  - `flood.lua`：按状态码分别计数；
  - `lim-all.sh`、`lim-ext.sh`、`lim.py`。
- 以上工具都已打包到 `results/tools.tgz`。

## 7. 建议

### 限流配置（推荐，只改配置）

在 `ticket-risk-web/nginx/default.conf` 的 `server{}` 外面加上 map 和 zone。conf.d 中的文件本身就处在 http 块里，所以可以直接写在这里：

```nginx
map $request_method $reservation_post_key { POST "post"; default ""; }
limit_req_zone $reservation_post_key zone=reservations:1m rate=100r/s;
limit_req_status 429;
upstream platform { server ticket-risk-platform:8082; keepalive 64; }

server {
  ...
  location = /api/tickets/reservations {
    limit_req zone=reservations burst=40 nodelay;
    error_page 429 = @reservation_busy;
    proxy_http_version 1.1; proxy_set_header Connection "";
    proxy_set_header Host $host; proxy_set_header X-Forwarded-For $remote_addr; proxy_set_header X-Real-IP "";
    proxy_pass http://platform;
  }
  location @reservation_busy {
    default_type application/json;
    return 429 '{"success":false,"errorMsg":"当前排队人数较多，本次请求未受理（不代表抢票失败），请使用原请求编号稍后重试","data":{"code":"SYSTEM_BUSY","reliableAccepted":false,"retryWithSameRequestId":true}}';
  }
  location /api/ { ...保持现状... }
}
```

- `rate` 按"单实例 HELD 能力 × 约 2.5"设置，这台机器上是 100。这个限额是每个 nginx 实例各自计算的。部署多个应用实例时，阈值按实例数线性放大，或者改用网关层的全局限流。
- Spring 的 `ticketing.ingress.post-rate` 保留作兜底，设为 150，略高于 nginx 阈值，正常情况下不会重复拒绝。
- 60/80 这两个更低的阈值本轮没有测到。如果要进一步调优，下次只需要补测这两个点，每个跑 3 轮。

### 能较快提升 HELD 的代码优化（只是方案，未实施，需要你确认）

| 优先级 | 改动 | 预期效果 | 是否影响语义 |
|---:|---|---|---|
| 1 | **在 `runtimePlan` 之前先检查一次容量**。V3Entry.accept 先读 Redis 里的容量（cap 加 ZCARD，或者在本地缓存一个 50～100 ms 的"已满"标志），已满就直接返回 429 | nginx 100 的配置下，约 56 个/秒最终被 BUSY 拒掉的请求不再执行 MySQL 事务；nginx 150/200 时省得更多 | 不影响：accept Lua 仍然做最终判断，预检只会让请求更早被拒，不会多放行 |
| 2 | **HTTP 入口缓存 `runtimePlan`**：按 session/sku/version 缓存，TTL 1 秒；每次命中都核对 Redis 中的 assembly-fence 和版本；不缓存否定结果 | 每个 POST 少执行 1 个带 LOCK IN SHARE MODE 的事务。MySQL QPS 预计下降，HELD 事务有更多 CPU 和锁资源可用 | 不影响：prepare、ordered_reserve 和最终 HELD 事务里的 `runtimeSku` 仍会以 LOCK IN SHARE MODE 重新校验；Lua 也会校验 fence、版本和销售时间窗。幂等、限购、终态、库存逻辑都不变 |
| 3 | AttemptAdmissionException 不再生成堆栈（writableStackTrace=false） | 每个 429 少一次填充堆栈的开销，改动很小 | 无 |
| 4 | **容量窗口从约 4 秒缩到 2～3 秒**（`V3StreamWorker.lastCap`） | nginx 100 时 202 后超时率是 2.75%，预计可以接近 0；代价是 429 稍多一些 | 只改受理节奏，不改任何终态逻辑 |
| 5 | **前端收到 429 且 `retryWithSameRequestId` 时停止轮询**，显示"未受理，不代表失败"，"使用原请求重试"按钮按 1～3 秒退避 | 避免被拒请求变成每秒一次、持续 120 秒的 GET 洪峰 | 只改前端展示；requestId 照旧保留 |

建议的顺序：先上线 nginx 配置，再按 1 → 3 → 2 → 4 实施，第 5 项和 nginx 配置一起改。每一步都只需要在同一个 JVM 里做一次短 A/B，单轮 15 分钟以内就能验证。

## 文件

`results/v3-ingress-limit-20261031/`：
- `lim-summary.json`：每次运行的指标和逐秒 HELD；
- `lim-runs.tgz`：各次运行的 capacity.json、nginx.conf、wrk 原始输出、test.log；
- `tools.tgz`：压测工具。
