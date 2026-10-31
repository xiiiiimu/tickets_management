# 领域迁移执行清单

来源：credit-risk-platform / credit-risk-web / credit-agent（消费贷风控）。目标：ticket-risk-platform / ticket-risk-web / risk-agent，原目录直接迁移，不建立并列 Demo。

## 扫描结论

Java 为 Maven 单模块，Spring Boot 2.3 / Java 8、MyBatis Plus、Redis、RocketMQ、Actuator；前端 React 18 / Vite / Ant Design；Python FastAPI / LangGraph。SQL 原先由人工执行，Compose 未挂载初始化脚本。hmcredit-risk-platform 仅残留 target；根目录压测报告、日志属于旧业务生成物。未发现 AGENTS.md。

| 分类 | 原路径/能力 | 处理 |
|---|---|---|
| KEEP | common、user、audit、workflow entity/mapper/persistence、缓存测试 | 迁移 package，保留可用实现和测试 |
| REFACTOR | WorkflowExecutionService、Redis 锁 | Java 驱动流程，令牌校验解锁 |
| REFACTOR | auth、config、trace、异常处理 | 统一 API 路径、角色校验、身份绑定、配置化 |
| REFACTOR | credit/mq/outbox、人工审核 | 提取事务 Outbox、重试、状态约束设计，替换领域实体 |
| REFACTOR | Python、前端、Docker、README | 在原模块内替换业务 |
| DELETE | credit、product、ocr、input、贷款 Agent/Tool/prompt、旧 SQL/测试/文档/压测 | 不保留失效兼容业务 |

现有未提交修改：AuditLogMapper、AuditLogService 的 LLM 统计原样迁移；AdminMetricsController 新增统计在票务指标接口保留。

## 阶段与验证

1. 基础设施迁移与旧域移除；编译、保留测试。
2. MySQL 票务模型、Lua 预占/幂等释放、CAS 订单、钱包、Outbox、MQ、规则与审核；编译及单元/集成测试。
3. Python 辅助分析、前端票务流程、Compose、监控与 JMeter；Python 测试、前端 build。
4. 全仓旧域扫描、最终验证、README 与验收记录。

## 一致性边界

- Redis 是预占闸门，MySQL 条件库存更新是持久化防超卖防线；用 reservation 行锁协调补偿与创建，避免提交结果不确定时错误释放。
- 数据库写入 reservation/order/outbox 同事务。MQ 至少一次投递，消费唯一键与 CAS 同事务。
- 支付幂等记录、钱包条件扣款、流水、订单状态同事务；退款与释放采用可重试事件。
- Python 无数据库权限，无最终决策权；无 Key 明确不可用。
- Docker 当前未运行，集成测试将明确记录实际执行或环境阻塞，不能把跳过当通过。

## 数据迁移

新数据库 ticketing 独立初始化；旧业务数据不转译成票务数据。历史数据库的删除仅提供显式脚本，不自动连接或清空现有实例。
