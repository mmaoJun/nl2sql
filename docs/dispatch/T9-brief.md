# T9 派发说明 — 聊天 HTTP 通道（同步 + SSE 流式）

你是实现子代理，只依据本文件执行，不读其他计划文档。完成后写报告到 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T9-report.md`。

## 背景

W2 已完成：5 个只读 @Tool、ReActAgent 双通道装配（本地 Ollama 文本化协议 / 云端 DashScope 原生 function calling），端到端实测通过（见 `docs/dispatch/T5-report.md`）。本任务把 Agent 能力暴露为 HTTP：**提问 → 流式看过程 → 拿结果**，供前端（T12）对接。**接口契约由本任务定义**（前端后续适配）；技术方案书只作大体参考。

参考（**只读**）：旧项目 `F:\FANG_ZHENG\nlp2sql\backend` 的 `ChatController` / `ChatRequest` / `ChatResponse`（旧管道时代，仅参考字段诉求与错误处理风格，不照抄结构）。

## 交付物

1. `service/ChatService`（`com.nl2sql.service`）：
   - `ChatResult ask(Long datasourceId, String question, String sessionIdOrNull)`：
     - 入参组装：问题包装为 `"数据源ID为 " + N + "。" + question`（满足工具与提示词约定）
     - 执行：注入 `ReActAgent` 默认 Bean，走 `agent.streamEvents(...)` 采集（事件类型参照 `test/.../AgentE2ESupport`，但生产侧自建采集器 `AgentEventCollector`，**不依赖 test 类**）
     - 采集 → `ChatResult`：`answer / sql / columns / rows / rowCount / trace / model / latencyMs / firstEventMs`
     - `trace` 元素：`{type: TOOL_CALL|TOOL_RESULT, tool, callId, input(截断 ~300), summary(截断 ~300), success}`
     - `sql/columns/rows`：取**最后一次成功 execute_sql** 的入参 SQL 与 ToolResult 内容（JSON → 解析）；解析失败则字段置空并在 trace 记原始摘要
   - 耗时：`firstEventMs`（首个事件）、`latencyMs`（完成）——对应"流式口径"的性能记录
   - 方法签名保留 `sessionId` 参数，注释注明 `// T11: 注入历史`（本轮忽略该参数）
2. `controller/ChatController`：
   - `POST /api/v1/chat`：body `{question, datasourceId, sessionId?}` → `ApiResponse<ChatResult>`（校验：question 非空、datasourceId 非空；错误用 `BusinessException` + 新增合适错误码段）
   - `GET /api/v1/chat/stream?question&datasourceId&sessionId`：`produces = MediaType.TEXT_EVENT_STREAM_VALUE`，返回 `Flux<ServerSentEvent<...>>`（Spring MVC + reactor 已具备）；SSE 事件名：`TEXT_DELTA`（data `{delta}`）/ `TOOL_CALL` / `TOOL_RESULT` / `FINAL`（载荷与 ChatResult 一致）/ `ERROR`（`{code, message}`），正常结束即 close，异常发 ERROR 后终止
   - TEXT_DELTA：先查 `io.agentscope.core.event` 包有无正文流式事件（javap 自查），有则映射；没有或本地通道（stream=false）缺失属**契约允许**——FINAL 必定送达
   - SSE 超时（如 5 分钟）与客户端断开处理按框架默认 + 合理兜底即可
3. DTO（`model/dto`、`model/vo`）：`ChatRequest` / `ChatResult` / `AgentTraceItem` / SSE 事件载荷。字段名以上文为准；实现中如微调，报告里必须写出**最终契约表**
4. 测试 `ChatControllerIntegrationTest`（真实本地模型；`assume` 守卫 MySQL+Ollama，模式参照 `AgentE2ESupport.assumeLocalStackUp`）：
   - 同步接口 1 例：对 `nlp2sql_test` 提简单问题（按库名查数据源，不硬编码 id），断言 answer 非空、rows 非空、latencyMs > 0
   - SSE 接口 1 例：收集事件流，断言出现 TOOL_CALL / TOOL_RESULT 且以 FINAL 收尾
   - **真实 LLM 预算硬约束：最多 2 次完整问答**；出现 7B 行为问题（如空回复）时最多修复 1 次，仍不稳定则如实记录现象并标注，**不做无限调参**（教训：T5 曾因此耗掉 30 分钟/179 次工具调用被截断）
5. 不改 T2-T5/T7 既有文件；确有必要的最小修改须在报告说明。

## 硬约束

- **不接认证**（T10 做）、**不做会话**（T11 做）——但签名留好 `sessionId`。
- 不建空包、空文件；不实现前端。
- git：只 `git add backend/` 本任务路径；**禁止 `git add -A` / `git add .`**；中文 conventional commit，2–3 个提交（服务与契约 / 控制器与 SSE / 测试）。
- 工作树中非本任务文件不要动。

## 验证（实际执行，真实输出进报告）

1. `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package`，37 例 + 新增全绿，贴 surefire 行。
2. 报告贴：同步调用的真实输出摘要（answer 前 200 字 + sql + rows 行数 + latencyMs/firstEventMs）；SSE 事件序列（前 ~10 个事件名与关键载荷）。

## 报告要求（写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T9-report.md`）

- 文件清单；**最终接口契约表**（HTTP 端点 + 字段）
- 验证命令真实输出关键行
- 与方案书 §5.4 草案的差异（如有）
- 偏差与自主决策；提交 hash；遗留问题（供 T10-T12）
