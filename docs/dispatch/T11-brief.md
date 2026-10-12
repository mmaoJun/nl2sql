# T11 派发说明 — 会话记忆（持久化 + 历史注入）

你是实现子代理，只依据本文件执行，不读其他计划文档。完成后写报告到 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T11-report.md`。

## 背景

T9/T10 已完成：聊天 HTTP 通道（契约见 `docs/dispatch/T9-report.md`）+ JWT 认证与审计（`docs/dispatch/T10-report.md`）。本任务实现**会话记忆**：会话/轮次持久化，多轮追问时把最近 N 轮历史注入 Agent。**明确不做追问改写**（QuestionRewriter/RewriteGate 不迁移——Agent 带历史后直接理解追问；旧 `resolved_question`/`rewrite_status` 字段留空）。

旧项目（**只读** `F:\FANG_ZHENG\nlp2sql\backend`）会话层已核实：
- `service/ChatSessionService.java`（238 行）：`create(userId, req)` / `list(userId)` / `listTurns(sessionId, userId)` / `recentTurns(sessionId, datasourceId, limit)`（**上下文按数据源隔离**）/ `rename` / `delete` / `requireOwned` / `insertTurn` / `applyFirstQuestionTitle` / `touch` —— 按此 API 迁移（userId 入参照旧实现）。
- `controller/SessionController.java`：`/api/v1/sessions`：POST 创建 / GET 列表 / GET `/{id}/turns` / PATCH `/{id}` 重命名 / DELETE。
- `model/ChatSession.java`、`model/ChatTurn.java`、`mapper/ChatSessionMapper|ChatTurnMapper`、`model/vo/SessionVO|TurnVO`、`model/dto/SessionCreateRequest|SessionRenameRequest`、`service/TurnRecord.java`（轻量注入载体，按需）。
- 表 `chat_sessions` / `chat_turns` 已在 `nlp2sql_meta`（旧结构，**无 answer 字段**）。

## 交付物

1. 上述会话层类迁移到 `com.nl2sql`（行为对齐；归属校验用 T10 已建立的 userId 解析路径）。
2. **schema 变更（必要）**：`chat_turns` 增加 `answer` 列（历史注入需要助手回答文本）——更新新仓库 `sql/init_backend.sql` 的建表语句；并给出本机库的 `ALTER TABLE chat_turns ADD COLUMN answer TEXT NULL ...` 语句并实际执行（报告贴执行输出）。如决定不复用 `TurnRecord`，说明理由。
3. **ChatService 集成**（修改 T9/T10 的文件）：
   - `ask/stream` 的 `sessionId` 参数生效：非空 → `requireOwned` → `recentTurns(sessionId, datasourceId, N)` 组装历史；为空 → 新建会话（标题取首问，`applyFirstQuestionTitle`）并把 `sessionId` 放进 `ChatResult`（**契约增量，T12 依赖**，同步写进报告契约表）
   - 历史注入方式：组装 `List<Msg>`（用户问 + 助手答 成对，为最近 N 轮）+ 当前包装问题（`"数据源ID为 " + N + "。" + question`），走 `agent.streamEvents(List<Msg>, ctx)`；若 AgentScope 会话状态另有更优机制，可改用并在报告说明
   - 问答结束（成功/失败/空回答）都落轮次：`question`、`datasourceId`、`modelName/modelProvider`、`generatedSql`、`rowCount`、`executionTimeMs`、`status`、`errorMessage`、**`answer`**；`resolved_question`/`rewrite_status` 留空；空回答的 status 用独立值（如 `EMPTY`），便于后续评测统计
   - 完成后 `touch(sessionId)`；SSE 路径在 FINAL 前落库
   - 配置：`nlp2sql`→`nl2sql.memory.context-turns: 3` 增加进 yml（数值同旧）
4. 测试：
   - 会话 CRUD 集成用例（注册用户 → 创建/列表/重命名/删除；他人会话访问被拒）——无 LLM
   - 轮次落库断言（字段核对，含 `answer`）
   - **多轮追问端到端 1 例**：同一会话连续两问，**第二问必须依赖第一问上下文**（设计一个脱离历史就答不对的追问），断言第二轮回答正确且轮次表有 2 条记录
   - **真实 LLM 预算硬约束：仅多轮用例的 2 次问答**；模型行为问题最多修 1 次，不无限调参
5. 不改 T2-T8 其他既有行为；T9/T10 相关文件修改属本任务预期范围，报告说明。

## 硬约束

- 不实现前端；不做追问改写。
- git：只 `git add backend/ sql/` 本任务路径；**禁止 `git add -A` / `git add .`**；中文 conventional commit，2–3 个提交（会话迁移与 schema / ChatService 集成 / 测试）。
- 工作树中非本任务文件（docs/ 等）不要动。

## 验证（实际执行，真实输出进报告）

1. `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package`：42 例 + 新增全绿（贴 surefire 行）。
2. 报告贴：ALTER 执行输出；多轮对话两问的真实轨迹摘要（两轮 answer + 工具序列关键行）；轮次表落库字段摘要（不含敏感值）。

## 报告要求（写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T11-report.md`）

- 文件清单；**契约增量表**（ChatResult.sessionId、sessions 五个端点、turns 端点——T12 直接对接）
- 历史注入机制说明（消息组装规则、数据源隔离语义、N 取值）
- 验证命令真实输出关键行；与旧实现的偏差及理由
- 提交 hash；遗留问题（供 T12）
