# T25 派发说明 — 后端安全与健壮性收尾（三个既定小项）

你是实现子代理，只依据本文件执行，不读其他计划文档。完成后写报告到 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T25-report.md`。

## 背景

W4 收尾阶段。以下三个小项均来自既有任务报告的遗留清单，**互相独立、逐项验收**。

## 交付项

### 项 1：SSE 取消/断线补审计与轮次落库（来源：T10 遗留"cancel 不写审计"）

- 现状（先读 `ChatService` 的 `stream`/`ask` 及 T11 的落库逻辑确认）：正常路径在 FINAL 前落 `chat_turns` + 审计；**客户端中途断开（Flux unsubscribe / 连接关闭）时不落库**——轮次丢失、审计缺失。
- 要求：**取消与异常路径同样落一条轮次 + 一条审计**（对齐 T10 审计参数语义；`status` 用既有 `failed` 或新增 `cancelled`，选择并说明；失败原因注明"客户端取消/连接中断"；`answer` 可为已拼出的部分或空，按现有字段约束执行）。
- 测试：集成用例构造"中途断开"（例如用 `java.net.http.HttpClient` 的流式 body 读几个事件后主动 close；或对 Flux 主动 cancel——构造方式自选，报告写清楚）。断言：`chat_turns` 与 `audit_logs` 出现对应会话/用户的最新记录。**真实 LLM 预算 ≤1 次问答**（尽量用短问题制造取消窗口；若取消窗口不稳定，允许用较大的 max-limit/长问题辅助，报告如实说明）。

### 项 2：Hive SQL 的 JSqlParser 专项校验用例（来源：T21 遗留"未过 JSqlParser 专项验证"）

- 在 `SqlSecurityCheckerTest` 补充 Hive 风格 SQL 用例（真实 Hive 语法探路）：
  - **应通过**：反引号标识符（如 \`name\`）、库名限定（`demo.orders`）、`LIMIT n`、`GROUP BY + ORDER BY`、常用函数（COUNT/SUM/AVG/ROUND）；如遇合法语法被误拒 → 最小修复 checker（报告写差异）
  - **应拒绝**：堆叠查询、带库名的 UNION 注入变体、注释绕过（变体形态）
- 已知背景（T2 遗留）：合法 UNION 查询曾被**保守拦截**——本任务**不要求**放开，评估后保留现状或放开（给出理由，报告说明）。

### 项 3：HDFS 适配器引导式报错（来源：T23 遗留 #2）

- `HdfsFileAdapter`（先读其 SQL 执行/表解析路径）对"SQL 引用了不存在的文件表"时，对齐 T23 的 API 适配器做法（见 `JsonTableExecutor` 的引导式报错）：errorMessage 含**可用文件表清单**（表名=文件名去 .csv 的约定提示），让模型自纠，而不是抛原始引擎报错。
- 测试：`HdfsFileAdapterIntegrationTest` 补 1 例（查询不存在的表 → `success=false` 且 errorMessage 包含可用表清单；`assume` 守卫沿用）。

## 硬约束

- **真实 LLM 预算：≤1 次问答**（仅项 1 使用）；模型行为问题最多修 1 次，不无限调参。
- 不改 T2-T24 既有行为（除上述最小修复，报告说明）。
- git：只 `git add backend/` 本任务路径；**禁止 `git add -A` / `git add .`**；中文 conventional commit，2–3 个提交（按项拆分更佳）。
- 工作树中非本任务文件不要动。

## 验证（实际执行，真实输出进报告）

1. `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package`：**66 例基线** + 新增全绿（贴 surefire 行）。
2. 报告逐项贴：取消路径的 chat_turns/audit_logs 落库证据；Hive SQL 用例列表与结果；HDFS 报错原文。

## 报告要求（写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T25-report.md`）

- 三项分别：改动文件、验证证据、决策说明（status 取值 / UNION 处置 / 取消构造方式）
- 偏差与自主决策；提交 hash；遗留问题
