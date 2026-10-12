# T5 派发说明 — ReActAgent 装配与端到端验证（W2 检查点）

你是实现子代理，只依据本文件执行，不读其他计划文档。完成后写报告到 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T5-report.md`。

## 背景

5 个 @Tool 工具已就绪（T4：`9f9707f`/`4d528a3`/`ab74755`，34 测试全绿）。本任务达成 **W2 检查点：ReActAgent 装配 + 端到端"自然语言→工具调用→结果"验证**。

模型双轨（《技术方案书》§2）：云端 qwen（dashscope 扩展）+ 本地 Ollama `qwen2.5-coder:7b`（openai 兼容扩展）。

**本机环境事实（已探测）**：
- `DASHSCOPE_API_KEY` 未设置（shell / 用户 / 机器环境变量均无）→ 云端验证写成"有 key 才跑、缺 key 跳过（assumeTrue）"的形态，**不得因此阻塞应用启动与测试编译**
- Ollama 服务运行中（http://localhost:11434），`qwen2.5-coder:7b` 已就位 → 本地端到端**必须真实跑通**
- 本机 MySQL 在跑（前面任务已用过）；Redis 未跑

## AgentScope 2.0.2 真实 API（已 javap 核实）

- `io.agentscope.core.ReActAgent`：`ReActAgent.builder().name(..).sysPrompt(..).model(Model 或 String).toolkit(..).maxIters(5).build()`（注意是 `maxIters` 不是 maxIterations）
- 调用：`agent.call(String question, RuntimeContext ctx)` → `Mono<Msg>`（有无单参重载自行 javap 确认）；`RuntimeContext.empty()` / `builder(...)` 可用
- 流式：`agent.streamEvents(Msg)` → `Flux<AgentEvent>`（W4 前端轨迹用，本任务不强求）
- 工具调用观测：`Toolkit.setChunkCallback(BiConsumer<ToolUseBlock, ToolResultBlock>)` 或 `io.agentscope.core.hook.Hook`（builder.hook(..)）——用哪种自行验证，用于断言"execute_sql 被调用过"
- 模型 SPI：扩展经 `META-INF/services/io.agentscope.core.model.spi.ModelProvider` 注册；`Builder.model(String)` 或直接构造 `DashScopeChatModel.builder()` / `OpenAIChatModel.builder()`（javap 查看 Builder 全部方法，确认 apiKey / baseUrl / modelName / temperature 的设置方式）
- jar：`E:\qqdownload\javaweb\warehouse\io\agentscope\` 下 `agentscope-core`、`agentscope-extensions-model-dashscope`、`agentscope-extensions-model-openai` 三个 2.0.2 jar，`javap -cp` 自查

## 交付物

1. `com.nl2sql.config.AgentConfig`：
   - `Toolkit` Bean（注册 T4 的 `NL2SQLTools`）
   - `ReActAgent` Bean（name=`NL2SQLAgent`，sysPrompt 见下，`maxIters=5`）
2. 模型装配与配置：
   - `application.yml` 增 `nl2sql.model` 段：`dashscope-api-key: ${DASHSCOPE_API_KEY:}`、`cloud-model: qwen-plus`、`ollama-base-url: http://localhost:11434`、`local-model: qwen2.5-coder:7b`（键名可按实现微调，报告说明）
   - 本地模型 Bean：OpenAI 兼容通道指向 Ollama（baseUrl `http://localhost:11434/v1`，apiKey 任意非空串如 `"ollama"`）
   - 云端模型 Bean：DashScope 通道；**缺 key 时不得导致启动失败**（条件装配或延迟创建，报告说明做法）
   - Agent Bean 默认装配**本地模型**（无 key 也能跑）；提供可切换（属性或 Bean 限定符）以便云端验证，做法报告说明——本任务不做完整 ModelRegistry 路由（W3/W4 再说）
3. sysPrompt v1：参考旧项目 `F:\FANG_ZHENG\nlp2sql\backend\src\main\resources\prompts\`（v1 模板，只读）提炼：只读硬约束、探索式流程（list_tables → get_schema → sample_data → validate_sql → execute_sql）、拒绝写操作、SELECT 带 LIMIT、中文回答。落在 `backend/src/main/resources/prompts/agent-v1.md`（格式自选并报告）。
4. 端到端验证（检查点核心）：
   - `AgentEndToEndTest`（`@SpringBootTest`，注入 ReActAgent）：用**本地 Ollama** 真实调用，对 `nlp2sql_test` 提 2 个问题（1 个简单查询 + 1 个聚合/分组，问题按实际表结构拟），断言：最终响应非空、捕获到至少一次 `execute_sql` 调用、回答包含真实数据痕迹。用 JUnit `@Timeout` 防挂（如 300s），实际耗时写进报告。**Redis 未跑，若上下文/会话组件报错需容错或绕开**。
   - `AgentCloudSmokeTest`：装配云端 ReActAgent 跑 1 个问题；`DASHSCOPE_API_KEY` 缺失时 `Assumptions.assumeTrue` 跳过并打印"待 DASHSCOPE_API_KEY"。**测试中不得硬编码任何 key**（只从环境变量读）。
5. 不改 T2/T3/T4/T7 已迁移文件（确有小 bug 才最小修 + 报告说明）。

## 硬约束

- 不建空包；不实现 Controller / SSE / 前端接口（后续任务）；`mvn package` 在无 key、无 Ollama 环境也须能编译通过（测试用 assume 守卫）。
- git：只 `git add backend/` 下本任务路径；**禁止 `git add -A` / `git add .`**；中文 conventional commit（建议 配置与装配 / sysPrompt / 端到端测试 2–3 个提交）。
- 工作树中非本任务文件不要动。

## 验证（实际执行，真实输出进报告）

1. `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package`；贴 surefire 行（已有 34 例 + 新增；本地端到端应实际执行非 skip）。
2. 报告贴：本地端到端的真机轨迹摘要（工具调用序列 + 最终回答前 ~300 字 + 耗时）。
3. 云端冒烟：key 可用则贴真实输出；否则写明"待 DASHSCOPE_API_KEY，用例已就位"。

## 报告要求（写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T5-report.md`）

- 文件清单；验证命令真实输出关键行
- 模型装配决策（缺 key 不阻塞的做法、双通道切换方式）
- sysPrompt 要点摘要
- 端到端轨迹样例（工具序列 + 回答摘录 + 耗时）
- 偏差与自主决策；提交 hash；遗留问题（供 W3 起）
