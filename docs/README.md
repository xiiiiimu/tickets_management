# 项目文档目录

更新日期：2026 年 9 月 18 日（风险链路语义重构：实时准入 + 后置 RiskProfile）。

| Word 文档 | Markdown 源文档 | 内容 |
|---|---|---|
| [系统完整结构](./word/01-系统完整结构文档.docx) | [Markdown](./01-系统完整结构文档.md) | 系统全貌、准入/画像双路径、状态机、事务边界 |
| [Java 后端面试 100 问](./word/02-Java后端面试100问.md) | [Markdown](./02-Java后端面试100问.md) | 100 题；含 RiskProfile 与主链改造 |
| [Agent 应用开发面试 100 问](./word/03-Agent应用开发面试100问.md) | [Markdown](./03-Agent应用开发面试100问.md) | Agent 只服务后置 REVIEW，不影响当前订单 |
| [接口数据库与细节](./word/04-接口数据库与细节问题.md) | [Markdown](./04-接口数据库与细节问题.md) | 接口、`tb_risk_profile`、Redis、MQ |
| [架构决策与取舍](./word/05-架构决策与取舍文档.md) | [Markdown](./05-架构决策与取舍文档.md) | 含 D34 RiskProfile |

相关：`../README.md`、`../interview-prep/项目复盘与面试准备.md`。

核心语义：**抢票请求只执行实时准入风控；深度风险分析在交易后异步执行，结果用于更新后续请求使用的风险画像，不回溯影响当前订单。**
