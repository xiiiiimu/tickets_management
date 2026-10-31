# Performance Acceptance Report

**Result directory:** `performance/results/20260918-094904/`  
**PERFORMANCE_ACCEPTANCE = PARTIAL**  
**Do not use** `performance/results/20260918-040002/` as formal evidence (historical incomplete / OOM run).

---

## 1. Test Environment

| Item | Value |
|---|---|
| OS | Microsoft Windows NT 10.0.26200.0 |
| CPU | 12th Gen Intel Core i7-1260P (12 cores / 16 logical) |
| RAM | 15.7 GB |
| Free RAM before formal run | ~2293 MB (host) |
| Docker | 29.7.2 |
| Platform | Docker image `credit_risk-ticket-platform:perf`, mem_limit 768m, `JAVA_TOOL_OPTIONS=-Xms128m -Xmx384m -XX:+UseSerialGC`, port `127.0.0.1:8082` |
| MySQL | Docker `mysql:8.0` (host port 3307) |
| Redis | Docker `redis:7-alpine` |
| RocketMQ | Docker `apache/rocketmq:4.9.7` |
| Risk-agent | Docker (no `OPENAI_API_KEY`; fallback/scripted path only) |
| JMeter | 5.6.3 via `ApacheJMeter.jar` non-GUI |
| JMeter heap | `-Xms64m -Xmx160m` |
| Client Java | Temurin 17.0.19 |
| HTML report | disabled (reduce client memory) |

Source: `environment.txt`, `run_config.txt`.

---

## 2. Method

- Concurrency ladder for stock contention: **20 / 50 / 100**, each repeated **3** times (9 runs).
- Scenarios executed **serially**; one JMeter process at a time; pause between runs.
- Each stock round: reset `perf_sku_stock20` MySQL rows + Redis keys → JMeter → **per-round** SQL validation.
- Additional scenarios (new directory, re-run): `duplicate_request`, `risk_profile_block`, `risk_pressure_reserve`.
- Initial stock for contention SKU: **20**. Purchase limit / Lua / DB constraints **unchanged**.

---

## 3. Stock Contention Gradient (HTTP + business)

SKU `perf_sku_stock20`, `initial_stock=20`.

| Concurrency | Run | Requests | HTTP success | System errors | Error% | Throughput* | Avg ms | P95 ms | P99 ms | remaining | valid_orders | bad_rows |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 20 | 1 | 20 | 0 | 20 | 100 | 12.76 | 30128 | 30423 | 30423 | 2 | 18 | 0 |
| 20 | 2 | 20 | 20 | 0 | 0 | 11.98 | 1240.55 | 1944 | 2019 | 0 | 20 | 0 |
| 20 | 3 | 20 | 0 | 20 | 100 | 17.47 | 30216.9 | 30442 | 30442 | 5 | 15 | 0 |
| 50 | 1 | 50 | 0 | 50 | 100 | 31.53 | 30114.86 | 30390 | 30391 | 0 | 20 | 0 |
| 50 | 2 | 50 | 0 | 50 | 100 | 29.1 | 30040.44 | 30210 | 30210 | 10 | 10 | 0 |
| 50 | 3 | 50 | 0 | 50 | 100 | 29.73 | 30052.74 | 30243 | 30243 | 0 | 20 | 0 |
| 100 | 1 | 100 | 0 | 100 | 100 | 140.25 | 30668.79 | 30740 | 30749 | 5 | 15 | 0 |
| 100 | 2 | 100 | 0 | 100 | 100 | 62 | 30095.92 | 30325 | 30326 | 0 | 20 | 0 |
| 100 | 3 | 100 | 0 | 100 | 100 | 62.31 | 30121.03 | 30399 | 30400 | 9 | 11 | 0 |

\*Throughput/latency on timeout-dominated runs are **not** usable as service capacity claims (client `SocketTimeoutException` ~30s). Only **20×r2** has a clean HTTP completion set.

### Gradient summary (correctness-focused)

| Concurrency | Runs | Clean HTTP runs | Avg RPS (all runs)* | Avg P95 (all)* | Error rate mean* | Correctness |
|---:|---:|---:|---:|---:|---:|---|
| 20 | 3 | 1/3 | mixed / mostly timeout | mostly ~30s timeout | high except r2 | **3/3 bad_rows=0** |
| 50 | 3 | 0/3 | timeout-dominated | ~30s timeout | 100% | **3/3 bad_rows=0** |
| 100 | 3 | 0/3 | timeout-dominated | ~30s timeout | 100% | **3/3 bad_rows=0** |

Clean latency sample (only): **20×r2** → TPS≈11.98, Avg≈1241ms, P95≈1944ms, P99≈2019ms, Error%=0, orders=20≤stock.

---

## 4. duplicate_request

| Metric | Value |
|---|---|
| Concurrency | 50 |
| Requests | 50 |
| HTTP success | 50 |
| Error% | 0 |
| Throughput | 30.23 rps |
| Avg / P95 / P99 | 2342 / 3084 / 3128 ms |
| Reservation count | **1** |
| Order count | **1** |
| Outbox count | 2 |
| bad_rows | **0** |

Invariant: same `requestId` → single business result. **PASS**.

---

## 5. risk_profile_block

| Metric | Value |
|---|---|
| Concurrency | 50 |
| Requests | 50 |
| HTTP codes | **409 × 50** (business BLOCK) |
| JMeter “Error%” | 100% (non-2xx counted as sample error) |
| business_rejected | 50 |
| system_errors | 0 |
| Throughput | 42.52 rps |
| Avg / P95 / P99 | 334 / 626 / 628 ms |
| Valid orders on `perf_sku_block` | **0** |
| Remaining stock | **100** (unchanged) |
| bad_rows | **0** |

Business outcome: BLOCK rejects before consuming stock. **Correctness PASS**. Do not treat JMeter Error% as system failure.

---

## 6. risk_pressure_reserve

| Metric | Value |
|---|---|
| Concurrency | 50 |
| Requests | 50 |
| HTTP | mostly `SocketTimeoutException` + some 503 |
| Error% | 100 |
| Throughput / latency | timeout-dominated (not capacity claim) |
| Remaining (`perf_sku_risk`, total 200) | 180 |
| Valid orders | 20 |
| bad_rows | **0** |
| OPENAI_API_KEY | absent → **real LLM latency/quality not validated** |

---

## 7. Correctness / Invariants

Per-round + final SQL checks (`validation-*.txt`, `validation-final.txt`):

| Check | Result |
|---|---|
| Negative stock | **0** all rounds |
| Orders over stock | **0** |
| Duplicate requestId reservations | **0** |
| Duplicate payments / issued tickets | **0** |
| Purchase over limit | **0** |
| BLOCK created orders/reservations | **0** |
| Final aggregate `validate_perf.sql` | **bad_rows=0** |

**Correctness conclusion:** under this run, **no oversell / no duplicate business records / BLOCK did not consume stock**.

---

## 8. Host Resource Limits

- Pre-run free host RAM ≈ **2293 MB**.
- Safe releases: removed exited platform container + Created orphan `priceless_maxwell`; non-project `docker-*` stack was already Exited; **no volume/DB prune**; no stop of unrelated running containers.
- This formal run: **`host_limit=False`**, **no JMeter/JRE OOM** in `20260918-094904`.
- Earlier aborted attempt (wrong ThreadLevels binding → ~2M threads) caused OOM; that run is discarded.

Dominant issue in formal results: **server-side latency / saturation** (client read timeout 30s, occasional 503), consistent with small reservation thread pool (`threadpool_max{pool="reservation"}=2` observed earlier) and single-instance Docker heap 384m — **not** classified as HOST_RESOURCE_LIMIT.

---

## 9. Performance Observations

1. Only **one** stock-contention run (20×r2) completed with full HTTP 200 set.
2. Other stock / risk_pressure runs often waited until JMeter **30s response timeout** while DB still recorded ≤stock successful orders — clients timed out waiting on a slow/saturated reserve path.
3. `duplicate_request` and `risk_profile_block` completed without client timeout and are usable for latency observation of those paths.
4. Do **not** quote timeout-run TPS/P95 as “system QPS”.

---

## 10. Explicit Split

| Dimension | Verdict |
|---|---|
| **Correctness** | PASS for all executed stock rounds + duplicate + BLOCK + risk_pressure invariants |
| **Performance capacity claims (20/50/100)** | **NOT established** except limited clean sample at 20×r2 |
| **Host/JMeter OOM in formal dir** | None |
| **Overall acceptance** | **PARTIAL** |

---

## 11. Resume-safe wording (honest)

Allowed:

- “在本地 Docker 单实例下，用 JMeter 对抢票链路做了 20/50/100 并发库存争抢（每档 3 次）与幂等/限购/BLOCK 场景验收；**业务不变量全部通过（无超卖、同 requestId 单结果、BLOCK 不占库存）**。”
- “同 requestId 50 并发重放：HTTP 全成功且仅 1 条 reservation/order。”
- “RiskProfile BLOCK：50 并发均返回业务拒绝（HTTP 409），库存未扣减。”

Not allowed:

- “支持 100 并发 / 生产级性能 / 系统 QPS=XXX”（多数档位被客户端超时主导，不能作为容量结论）。
- 引用 `20260918-040002` 或不完整历史数字。

---

# Round 4：剩余瓶颈验证

**Result directory:** `performance/results/round4-20260918-213146/`  
**Goal:** 对照实验验证容量边界与 drain 慢因，**不是**继续堆 QPS。  
**No:** SKU 分桶 / 分库分表 / 盲目扩 Hikari·Tomcat·MQ consumer / 改 Risk 语义 / git push。

## 1. 背景

Round 3 后：reserve HTTP 与 AutoPay 已解耦（Outbox+RocketMQ），50 并发单 SKU 可 drain，但成功 reserve QPS 仍约 17–18，Hikari pending@50≈40+。怀疑 Hot SKU MySQL 写竞争 + 共享连接池，需对照验证。

## 2. 为什么不继续直接做库存分桶

在未用单/多 SKU 对照证明「热点行写」权重前，分桶会同时改变一致性模型、运维与测试面，无法把收益归因到单一变量。本轮先做实验收敛结论。

## 3. 实验 A：单 SKU vs 多 SKU（10 SKU）

方法：相同线程/用户/quantity/JVM/Hikari=10/实例；仅改变流量是否集中到同一 `sku_id`。库存足够大。QPS=测量窗 HTTP200/60s。

### A 表（真实结果）

| 模式 | 并发 | SKU数 | TPS(success_qps) | Avg | P95 | P99 | Hikari Pending Peak(during) | drain WAITING | bad_rows |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| single | 20 | 1 | 16.77 | — | 1728 | 2004 | 11 | 0 | 0 |
| single | 50 | 1 | r1=18.05 / r2=17.18 / r3=17.72 **median=17.72** | — | **4527** | ~5411 | ~40–44 | 0 | 0 |
| single | 100 | 1 | 17.78 | — | 9314 | 11113 | 91 | 0 | 0 |
| multi | 20 | 10 | **34.92** | — | **1000** | 1176 | 11 | 1691 | 0 |
| multi | 50 | 10 | r1=35.73 / r2=36.8 / r3=38.37 **median=36.8** | — | **2268** | ~2735 | ~40–41 | 2173–2925 | 0 |
| multi | 100 | 10 | **38.25** | — | 4246 | 5364 | 89 | 2807 | 0 |

**单 vs 多 @50：** multi median QPS **36.8 ≈ 2.07×** single **17.72**；P95 **2268 vs 4527（约减半）**。  
Hikari pending@50 两模式都≈40（连接池仍满载排队），但吞吐显著上升 → 支持「单 SKU 热点写竞争是主要容量瓶颈**之一**」，**不能**表述为「唯一瓶颈就是 SKU 行锁」。

**附加观察：** multi 下 Outbox/支付 drain 在 120s 内无法清空（WAITING 上千）——reserve 吞吐提高后，**履约链路成为第二瓶颈**（见实验 C）。

## 4. 实验 B：少库存 + 高入口流量

SKU stock=**20**，每档总请求 **1000**（threads×loops），quantity=1。

| 并发 | 总请求 | 入口 RPS | 成功订单(PAID/ISSUED) | 业务拒绝(409) | 5xx/timeout | P95 | P99 | bad_rows |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 50 | 1000 | 56.23 | HTTP200=20；drain 窗内支付受历史 backlog 干扰→PAID 统计为 0* | 980 | 0 | 1276 | 1656 | 0 |
| 100 | 1000 | r1=65.95 / r2=61.97 / r3=60.57 **median=61.97** | **20/20** | 980 | 0 | ~2008–2109 | ~2471–2584 | 0 |
| 200 | 1000 | 68.54 | **20/20** | 980 | 0 | 3996 | 5043 | 0 |

\*B@50：`remaining_stock=0` 且 HTTP200=20，无超卖；支付清空受前序 multi 残留 Outbox 影响，以 B@100×3 为主结论。

**入口吞吐 ≠ 完整成功交易吞吐：**  
入口 RPS≈**62**（售罄后大量快速 409）；完整成功交易≈**20 单 / 测量时长**量级（受库存硬顶），成功路径 TPS 仍接近单 SKU 平台期，**不能**把「~17 成功 reserve QPS」说成「系统每秒只能接 17 个请求」。

正确性：成功订单 ≤ 20；无负库存；无重复支付（bad_rows=0）；无超卖。

## 5. 实验 C：Payment / ISSUE drain 分段

专用干净库运行：`C_drain_t50`（50 并发，清理全部 `perf_sku_r4_%` Outbox 后），967 单全部 ISSUED。  
时间戳来源：outbox/consume/payment/order 的 `created_at`/`updated_at`（MySQL TIMESTAMP **秒级**，故 Payment 事务多为 0ms，不可解读为「零耗时」）。

| 阶段 | Avg | P50 | P95 | P99 | Max |
|---|---:|---:|---:|---:|---:|
| AUTO_PAYMENT Outbox 创建→SENT | 57249 | 59000 | 61000 | 62000 | 62000 |
| AUTO MQ 等待(SENT→consume) | 65 | 0 | 1000 | 1000 | 3000 |
| Payment 事务(秒级分辨率) | ~0* | 0 | 0 | 0 | 0 |
| ISSUE Outbox 创建→SENT | 63464 | 62000 | 69000 | 105000 | 128000 |
| ISSUE MQ 等待 | 54 | 0 | 0 | 2000 | 3000 |
| ISSUE consumer | ~1 | 0 | 0 | 0 | 1000 |
| 总履约(T0→ISSUED) | **120789** | 121000 | 127000 | 129000 | 133000 |

**最慢阶段 = Outbox 发布排队（AUTO_PAYMENT + ISSUE）**，不是 MQ 网络、也不是 Payment/ISSUE DB 事务本身。这解释了 Round3 drain≈78–110s：履约延迟被 Outbox publisher 吞吐卡住。

Payment consumer 线程模型：`RocketMQ consumeThreadMax=16`，AUTO_PAYMENT **直接**在 MQ 线程调用应用服务（无嵌套 autopay TaskPool）。`autopay` pool active/reject 全程 0。

## 6. Hikari 10 / 15 / 20 对照（50 并发单 SKU，各 2 次）

| MaxPoolSize | TPS (r1/r2) | P95 | P99 | Pending Peak(during) |
|---:|---:|---:|---:|---:|
| 10 | 15.98 / 16.87 | 4794 / 4696 | 5744 / 5691 | 41 / 42 |
| 15 | 15.45 / 17.45 | 4726 / 4293 | 5806 / 5211 | 38 / 33 |
| 20 | 15.68 / 16.62 | 4632 / 4400 | 5281 / 5269 | 33 / 31 |

TPS **未随连接池同比提升**（仍落在 ~15–17）；pending 略降但平台期仍在。支持：「瓶颈不是单纯连接池太小，而是下游 DB 写竞争 + 履约排队」——**连接池不是唯一瓶颈**。  
（注：H 组 drain WAITING 偏高，因紧随 multi 实验后 Outbox 全局积压；**窗口内 QPS/P95 仍可用于对照**。）

## 7. SQL / 索引检查

`EXPLAIN` 见 `explain/hotpath.txt`：

- `tb_ticket_sku` / `tb_user_purchase` 条件更新：**PRIMARY const/range**，无全表扫描。
- Outbox due 查询：走 `idx_outbox_due`，但因 CASE 优先级排序存在 **Using filesort**（吞吐问题更多来自 syncSend 批处理速率，非缺 PK）。
- **本轮未补索引**（热路径已按预期用主键；不做无关扩索引）。

## 8. 事务范围检查

`ReservationTransactionService.reserve` 事务内含：库存 CAS、限购、reservation/order、**Outbox（必须同事务）**、以及 `workflow.record`×2（多表审计写）。  
`workflow.record` 可考虑 afterCommit 缩短临界区，但会改变审计与业务提交的原子性边界；**本轮未改代码**，避免混淆对照实验归因。

## 9. 当前瓶颈结论（克制）

1. 单/多 SKU 对照显示：**热点库存写竞争是当前成功交易容量瓶颈的重要组成部分**（~2× QPS，P95 明显改善）。  
2. 共享 Hikari 在高并发下放大排队（pending@50≈40，@100≈90），但 **10→20 未带来同比吞吐**。  
3. Outbox+RocketMQ 改善了支付可靠性；**并未提高**单 SKU reserve 写吞吐；且在 multi 高产订单时，**Outbox 发布成为履约 drain 主慢点**。  
4. 入口售罄场景入口 RPS≃60+，与完整成功交易吞吐必须分开陈述。

## 10. 后续架构级优化（只设计，不实现）

| 方向 | 潜在收益 | 一致性复杂度 | 适用 | 本轮不做原因 |
|---|---|---|---|---|
| SKU 库存分桶 | 降低单行写竞争 | 中高（聚合/对账） | 极端热点票 | 需更强生产证据与模型设计 |
| 热点库存分片 | 水平扩展写 | 高 | 超大活动 | 超出本项目演示边界 |
| 核心/非核心库隔离 | 保护履约连接 | 中 | 支付与库存争用时 | Hikari 对照未显示「只差连接数」 |
| 多实例水平扩展 | 总入口容量 | 会话/幂等/锁 | 多机部署 | 单实例已足够暴露热点 |
| 入口排队/资格令牌 | 保护下游 | 低–中 | 秒杀入口 | 不解决热点写本身 |
| Redis 主协调+异步账本 | 入口极致加速 | 很高 | 超大规模 | 改变库存一致性模型 |
| 分库分表 | 长期扩展 | 很高 | 平台级 | 过度设计 |

## 11. 本轮选择「不继续大改」的原因

实验已足够支撑工程结论；继续分桶会污染归因并引入正确性风险。性能工程下一动作应是**有意识的架构变更 + 新一轮对照**，而非在本轮混入。

### 原始证据

- `summary.csv` / `summary_table.txt` / 各 `*.jtl` / `metrics-*.txt` / `validation-*.txt`  
- `prometheus-*` / `pools-*.txt`  
- `explain/hotpath.txt`  
- `drain/drain-latency-C.csv` / `drain-summary-C.txt` / `C_drain_t50.jtl`  
- `environment.txt`
