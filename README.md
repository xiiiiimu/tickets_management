# Ticket Risk Platform

一个集演唱会抢票、库存预占、订单支付、出票及实时风控于一体的票务系统，包含Java后端、Web前端和可选的异步Agent调查服务。

## 主要功能

- **开售前装配**：TicketSaleArmory装配场次、SKU和16桶库存，管理READY状态、版本发布与恢复。
- **异步抢票**：身份校验、限流和Redis Stream受理，Worker执行资格判断与可靠锁单。HTTP 202表示受理处理中，最终结果以MySQL为准。
- **库存一致性**：Redis Lua原子预占，支持数量与限购校验；MySQL持久化预占意图、库存分配、订单及Outbox，配合幂等、租约和失败补偿。
- **实时风控**：PASS / REVIEW / BLOCK画像判断；REVIEW支持快速复核与短期结果缓存，BLOCK拒绝预占。
- **交易履约**：预占成功后进入10分钟待支付流程，保留支付、超时释放、退款与出票链路。
- **事后调查**：通过Outbox与RocketMQ衔接异步任务；Agent调查更新后续交易使用的风险画像，不参与当前抢票放行。
- **运行观测**：Micrometer、Prometheus与Grafana指标，配套正确性测试和性能诊断工具。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java、Spring Boot、JdbcTemplate、MySQL、Redis、RocketMQ |
| 前端 | React、TypeScript、Vite、Ant Design |
| Agent服务 | Python、FastAPI、LangGraph |
| 开发与监控 | Docker Compose、Micrometer、Prometheus、Grafana |

## 核心流程

```text
开售前装配READY
  → 用户提交抢票请求 → 身份校验 / 限流 → Redis Stream受理 → 返回202
  → Worker原子领取 → Armory版本检查 / 风险资格判断
  → Redis库存预占 → MySQL提交HELD及待支付订单
  → 支付 → 出票

旁路：Outbox / RocketMQ → 异步风险调查 → 更新后续交易画像
```

## 项目结构

```text
ticket-risk-platform/   Java后端及测试
ticket-risk-web/        Web前端
risk-agent/             异步Agent服务
docker/                独立开发环境与监控配置
performance/           性能测试、诊断脚本及说明
docs/                  架构与使用文档
```

## 本地运行

先参考[Docker开发环境说明](docker/credit_risk/README.md)准备依赖，按`.env.example`和应用配置填写本地连接信息，再启动所需模块：

```powershell
# 后端
cd ticket-risk-platform
mvn spring-boot:run

# 前端（另开终端）
cd ticket-risk-web
npm install
npm run dev
```

实际启用的交易协议与功能由配置及对应数据库结构共同决定。Agent为独立服务，按需启动。真实密码、运行数据、构建产物与压测原始结果不纳入源码仓库。
