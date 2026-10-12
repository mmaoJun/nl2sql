# T11 实施报告 — 会话记忆（持久化 + 历史注入）

- 状态：**完成**。全量 `mvn -q clean package` **EXIT=0**，50 例全绿（新增 8 例；Skipped 1 = 云端冒烟缺 key 按设计跳过）。
- 提交：`344e8bb`（会话迁移与 schema）→ `8812821`（ChatService 集成）→ `f7b243c`（测试）
- 环境：本机 MySQL 8.0.41 ✓、Ollama `qwen2.5-coder:7b` ✓、Redis 未运行（不影响聊天链路）
- 旧项目 `F:\FANG_ZHENG\nlp2sql` 全程只读参考，未做任何修改；工作树中 `docs/` 等非本任务文件未动（本次仅 `git add backend/ sql/` 下本任务路径）

## 1. 文件清单

新增（主代码，`com.nl2sql`）：

| 文件 | 说明 |
|---|---|
| `model/ChatSession.java`、`model/ChatTurn.java` | 会话 / 轮次实体（`ChatTurn` 新增 `answer` 字段） |
| `mapper/ChatSessionMapper.java`、`mapper/ChatTurnMapper.java` | 含 `selectSessionList`（轮次数 + 预览一次带出）与 `touch` |
| `model/vo/SessionVO.java`、`model/vo/TurnVO.java` | 会话 / 轮次视图（`TurnVO` 新增 `answer`） |
| `model/dto/SessionCreateRequest.java`、`model/dto/SessionRenameRequest.java` | 会话请求体（均无 userId 字段，归属只由 JWT 决定） |
| `service/ChatSessionService.java` | 会话资源服务（归属校验 / 历史读取 / 轮次落库 / 标题 / touch） |
| `service/TurnRecord.java` | 轮次落库载体（**复用**旧项目载体，见 §5.2） |
| `controller/SessionController.java` | `/api/v1/sessions` 五个端点 |
| `common/TurnState.java` | 轮次状态常量：`ok` / `failed` / 新增 `empty` |
| `config/MemoryProperties.java` | `nl2sql.memory.context-turns`（默认 3，数值同旧） |

新增（测试）：`controller/SessionIntegrationTest.java`（3 例，零 LLM）、`service/ChatSessionServiceIntegrationTest.java`（4 例，零 LLM）、`controller/SessionMemoryIntegrationTest.java`（1 例，2 次真实问答）。

修改：

| 文件 | 改动 |
|---|---|
| `service/ChatService.java` | 会话接入：自动建会话 / 归属校验 / 历史注入 / 轮次落库 / 审计归因（保留原双通道采集口径与降级哲学） |
| `model/vo/ChatResult.java` | 新增 `sessionId`（契约增量，T12 依赖） |
| `model/dto/ChatRequest.java` | `sessionId` 由 String 改为 **Long**（T9 时期该参数被忽略，无行为破坏） |
| `controller/ChatController.java` | `sessionId` 查询参数改 Long；新增 `MethodArgumentTypeMismatchException → 4001`（防非数字 sessionId 落到 500） |
| `resources/application.yml` | 新增 `nl2sql.memory.context-turns: 3` |
| `sql/init_backend.sql` | `chat_turns` 建表加 `answer TEXT`、`status` 注释加 `empty`，并附本机库 ALTER 语句 |
| `controller/ChatControllerIntegrationTest.java` | 审计断言改为"会话归因非空"并补轮次落库核对；SSE FINAL 补 `sessionId` 断言（属 T9/T10 文件的本任务预期修改） |

## 2. 契约增量表（T12 直接对接）

### 2.1 Chat 通道增量（`POST /api/v1/chat`、`GET /api/v1/chat/stream`）

| 项 | 契约 |
|---|---|
| 请求 `sessionId` | **String → Long**（`chat_sessions.id`）。非空 → 归属校验 + 历史注入；为空 → 服务端自动新建会话。非数字值 → HTTP 200 + `{code:4001}` |
| 响应 `ChatResult.sessionId` | **新增** `Long`：本轮所属会话 id。自动新建时回填；无会话模式（建会话失败降级）时字段缺省（`non_null` 序列化省略） |
| SSE `FINAL` 载荷 | 同 `ChatResult`，**含 `sessionId`**（实测 `"sessionId":152`） |
| 新增错误 | `3001` 会话不存在或不属于当前用户（HTTP 200 + body 码）；`401` 未认证使用会话（HTTP 200 + body 码）。**流式入口在流开始前抛出**，与参数错误同路径 |

### 2.2 会话端点（`/api/v1/sessions`，均需 `Authorization: Bearer`，未认证 403）

| 方法 | 路径 | 入参 | 成功返回 | 失败 |
|---|---|---|---|---|
| POST | `/api/v1/sessions` | body `{title?, datasourceId?}` | `{code:200, data:SessionVO}` | 标题超 100 字 → HTTP 400 |
| GET | `/api/v1/sessions` | — | `{code:200, data:SessionVO[]}`（按 `updatedAt` 倒序，最多 50，含 `turnCount`/`preview`） | — |
| GET | `/api/v1/sessions/{id}/turns` | — | `{code:200, data:TurnVO[]}`（时间正序，最多最近 100 轮，**不含结果行**） | 3001 |
| PATCH | `/api/v1/sessions/{id}` | body `{title}` | `{code:200, data:SessionVO}` | 3001；标题空白 → HTTP 400 |
| DELETE | `/api/v1/sessions/{id}` | — | `{code:200, data:null}`（级联删轮次，**审计不删**） | 3001 |

`SessionVO`：`id / title / datasourceId / turnCount / preview / updatedAt`。
`TurnVO`：`id / question / datasourceId / modelName / modelProvider / generatedSql / rowCount / executionTimeMs / status / errorMessage / **answer**（新增）/ createdAt`（`resolvedQuestion`/`rewriteStatus` 恒空/`skipped`）。

## 3. 历史注入机制说明

- **组装规则**：`sessionId` 非空时取「同会话 + 同数据源」最近 `N=3` 轮，每轮展开为两条消息
  `[USER: "数据源ID为 N。" + question]`、`[ASSISTANT: answer]`（历史用户消息同样带数据源包装，与当前问题格式一致），末尾追加当前包装问题，整体交给 `agent.streamEvents(List<Msg>, ctx)`。
- **跳过规则**：`question` 或 `answer` 为空的轮次不注入（失败/空回答轮次没有助手文本，空 assistant 消息语义与合法性都不成立）。
- **数据源隔离**（`ChatSessionService.recentTurns`）：不同库的表结构不同，换库后的第一轮取到空历史 = 该库下的首轮。
- **N 取值**：`nl2sql.memory.context-turns: 3`（同旧项目）；`limit` 来自配置而非用户输入。
- **Agent 会话状态**：`RuntimeContext.sessionId` 仍为每轮随机（`chat-<uuid>`）。评估过 AgentScope 自带会话状态记忆（`InMemoryAgentStateStore` + `AgentStateMemoryView`），因其为进程内内存态、无法按数据源隔离、且与注入的历史叠加会形成双份记忆而**不采用**：多轮上下文的唯一来源是 `chat_turns`。
- **降级**：历史读取失败 → 按无历史继续；自动建会话失败 → 按无会话模式继续（不落轮次、`sessionId` 缺省）；**归属校验不降级**（3001）。
- **落库**：成功 / 失败 / 空回答都写 `chat_turns`（`status` = `ok` / `failed` / `empty`），字段 `question / datasourceId / modelName / modelProvider / generatedSql / rowCount / executionTimeMs / status / errorMessage / answer`；`errorMessage` 落库前截断到列宽 500；随后 `applyFirstQuestionTitle`（仅在占位标题时生效）+ `touch`。SSE 路径在 `FINAL` 事件前落库（先落库后发事件）。
- **审计归因**：`audit_logs.session_id / turn_id` 由固定 null 改为真实值（写入失败时 `turn_id` 仍为 null）。

## 4. 验证（真实输出）

### 4.1 schema 变更执行输出（本机库 `nlp2sql_meta`）

```sql
ALTER TABLE chat_turns ADD COLUMN answer TEXT COMMENT '本轮助手回答文本，历史注入用；失败/空回答时为 NULL';
```

```
Field	Type	Null	Key	Default	Extra
answer	text	YES		NULL
```

### 4.2 全量构建

`cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package` → **EXIT=0**（`-q` 静默 INFO，surefire 汇总行取自 `target/surefire-reports/*.txt`）：

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- DataSourceAdapterIntegrationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 1 -- AgentCloudSmokeTest（缺 key 按设计跳过）
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- AgentEndToEndTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- AuthIntegrationTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- ChatControllerIntegrationTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- SessionIntegrationTest                （新增）
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- SessionMemoryIntegrationTest          （新增）
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0 -- SqlSecurityCheckerTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- ChatSessionServiceIntegrationTest     （新增）
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- MetadataServiceIntegrationTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- NL2SQLToolsSecurityTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- NL2SQLToolsTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- ToolkitRegistrationTest
合计：Tests=50 Failures=0 Errors=0 Skipped=1（原 42 + 新增 8）
```

### 4.3 多轮追问端到端真实轨迹（`SessionMemoryIntegrationTest`，sessionId=152）

```
[T11-memory] 会话: sessionId=152 userId=48 title=users 表中共有多少位用户？ datasourceId=5
[T11-memory] 第1轮 question=users 表中共有多少位用户？
[T11-memory] 第1轮 answer=用户信息表中共有 200 位用户。
[T11-memory] 第1轮 sql=SELECT count(*) FROM users rowCount=1
[T11-memory] 第1轮 tools=[list_tables, get_schema, execute_sql] latencyMs=5631 firstEventMs=0
[T11-memory] 第2轮 question=我刚才问的第一个问题是什么？
[T11-memory] 第2轮 answer=你刚才问的第一个问题是：“数据源ID为 5。users 表中共有多少位用户？”
[T11-memory] 第2轮 sql=null rowCount=0
[T11-memory] 第2轮 tools=[] latencyMs=1041 firstEventMs=0
```

**历史注入直接证据**：第二问回答原样复读了第一轮被注入的历史用户消息（含服务端加的 `数据源ID为 5。` 包装前缀），且本轮无任何工具调用 —— 该信息库里查不到，只能来自注入的历史。

### 4.4 轮次表落库字段摘要（同一会话，2 条）

```
turn#233 status=ok answer(前 60 字)=用户信息表中共有 200 位用户。 rowCount=1 executionTimeMs=5631
         model=qwen2.5-coder:7b/ollama rewriteStatus=skipped resolvedQuestion=null
         createdAt=2026-10-12T12:15:11        question=users 表中共有多少位用户？  generatedSql=SELECT count(*) FROM users
turn#234 status=ok answer(前 60 字)=你刚才问的第一个问题是：“数据源ID为 5。users 表中共有多少位用户？”
         rowCount=0 executionTimeMs=1041 model=qwen2.5-coder:7b/ollama rewriteStatus=skipped
         resolvedQuestion=null createdAt=2026-10-12T12:15:12
         question=我刚才问的第一个问题是什么？  generatedSql=null（本轮无 SQL，属正常）
```

（测试结束即清理会话与轮次；同一会话的审计记录保留，DB 实查佐证归因：）

```
id=200 user_id=48 datasource_id=5 nl_input=users 表中共有多少位用户？ session_id=152 turn_id=233 model=qwen2.5-coder:7b/ollama
id=201 user_id=48 datasource_id=5 nl_input=我刚才问的第一个问题是什么？   session_id=152 turn_id=234 model=qwen2.5-coder:7b/ollama
```

### 4.5 其他实测摘要（零 LLM）

- 会话 CRUD：新建（占位标题"新对话"）→ 列表可见 → 轮次空 → 改名落库 → 删除 → 再查轮次 3001。
- 越权：他人查轮次 / 改名 / 删除 = `3001`（HTTP 200），无 token = `403`，空标题改名 = `400`。
- 服务层：`recentTurns` 只返回同数据源轮次、时间正序、`limit=1` 取最近一轮；首问标题只填占位值、用户改名不被覆盖、超长问题截断 30 字。
- 聊天回归（T9/T10 用例）：审计 `sessionId=148 turnId=232` 非空、轮次 `answer` 与响应 `answer` 一致、SSE `FINAL.sessionId` 非空。

## 5. 与旧实现的偏差及理由

1. **不迁移 `ConversationService` / `RewriteGate` / `QuestionRewriter`**：按派发说明"明确不做追问改写"。会话侧编排（建会话、历史组装、落库、touch）收拢在 `ChatService` 的三个私有方法里（`openSession` / `buildMessages` + `loadHistory` / `recordTurn`），归属校验仍单独收口在 `ChatSessionService`（安全边界不参与降级）。
2. **复用 `TurnRecord`（未弃用）**：同步/流式两条通道共用同一次落库口径，十余个实参需要具名顺序约束；但**去掉** `resolvedQuestion` / `rewriteStatus`（本项目不做改写），**新增** `answer`。旧 `TurnState` 的 `REWRITE_*` 常量同理不迁。
3. **新增 `TURN_EMPTY` 状态**：空回答不是执行失败（SQL 可能已跑成），单列一档便于后续评测统计（派发说明要求"空回答的 status 用独立值"）。
4. **`rewrite_status` 由 DB 默认值兜底**：实体不写该列 → 落库 `skipped`（"本轮不需要改写"的既有语义），`resolved_question` 为 NULL；未在代码里显式赋值，避免留下"改写层存在"的错觉。
5. **`MemoryProperties` 只保留 `context-turns`**：`rewrite-enabled` / `context-max-age-minutes` / `rewrite-model` 服务的是改写与话题闸门，本项目没有对应运行时行为，不留下没人读的配置。
6. **自动建会话是本项目新增行为**：旧项目前端先调 `POST /api/v1/sessions` 再提问；新契约要求"sessionId 为空即新建并回填"，故 `ChatService` 内部构造 `SessionCreateRequest` 调 `ChatSessionService.create`（标题留空，由 `applyFirstQuestionTitle` 在首轮补）。
7. **历史注入用消息列表，不用 Agent 会话状态**：见 §3（内存态、不可按数据源隔离、会形成双份记忆）。
8. **历史只注入"有助手回答"的轮次**：旧改写层只取 `(question, resolvedQuestion)` 文本、不涉及 answer；新机制需要成对的助手回答，空回答轮次被跳过（`loadHistory` 里 info 日志留痕）。
9. **`sessionId` 类型 String → Long**：会话标识即 `chat_sessions.id`；T9 时期该参数仅透传给 AgentScope 且被忽略，改型无行为破坏。为防老前端传字符串，`ChatController` 增加 `MethodArgumentTypeMismatchException → 4001`。
10. **sessions 的标题校验仍走 `@Valid`（HTTP 400）**：与旧实现一致，也与 T10 既有 `GlobalExceptionHandler` 分支一致；chat 通道的 `4001`（HTTP 200）语义不变 —— 前端需按两个入口分别处理，已列入遗留问题。
11. **`errorMessage` 落库前截断 500 字符**：列宽 `VARCHAR(500)`，超长会把整条轮次写入打回（而轮次写入失败是静默降级，反而更难发现）。
12. `SessionVO` / `TurnVO` / DTO / Mapper 的自定义 SQL 与旧实现逐字对齐（含 `preview` 的 `COALESCE(resolved_question, question)` 与 `LEFT(...,40)`）；`TurnVO` 仅新增 `answer`。

## 6. 真实 LLM 预算披露（硬约束）

- **新增消耗 = 6 次问答 / 3 次运行**（均为同一用例的 2 问，符合"仅多轮用例的 2 次问答 + 最多修 1 次"）：
  1. 全量首跑（含多轮用例 2 问）：多轮用例失败 —— 初版第二问为数值追问（"orders 的订单数比刚才的用户数多多少"），历史注入已生效（该轮消息角色序列实测 `[system, user, assistant, user]`，模型未调工具直接心算），但 7B 模型把 500-200 答成"多 50 个"，属**模型行为问题**；
  2. **唯一一次修复**：第二问改为纯历史回放型追问（"我刚才问的第一个问题是什么？"，不涉算术、库里查不到），定向复跑 `SessionMemoryIntegrationTest` → 1/1 通过；
  3. 最终全量复跑 `mvn -q clean package` → EXIT=0，50 例全绿。
- 未做任何提示词 / 温度调参；未出现空回复、工具格式异常之外的问题；未触碰"仍不稳定"分支。
- 说明：全量构建本身还会运行 T5/T9 的既有真实模型用例（`AgentEndToEndTest` 2 问 + `ChatControllerIntegrationTest` 2 问，共 4 问/次，属既有基线，与 T9/T10 同口径），T11 新增消耗仍为多轮用例的 2 问 × 3 次运行。

## 7. 遗留问题（供 T12）

1. **前端必须回传 `sessionId`** 才能形成多轮：第一问不带 → 用响应 `ChatResult.sessionId` 保存 → 后续追问带上（GET 流式入口的 `sessionId` 是数字查询参数）。
2. **`sessionId` 缺省（无会话模式）**：仅当自动建会话失败（DB 异常）时出现，前端应容忍并在下一轮重新尝试。
3. **`turns` 接口不含结果行**：历史只回 `SQL / 行数 / 耗时 / answer`；要数据需重新提问（与旧项目一致的设计）。
4. **两个通道的错误码风格不同**：chat = HTTP 200 + body `code`(4001/4002/3001/401)；sessions = 3001/401 走 HTTP 200 + body 码，但标题校验走 HTTP 400。T12 需按入口区分。
5. **SSE 起流前错误的报文形态**：T10 已记录 —— `Accept` 只含 `text/event-stream` 时拿不到错误 JSON；建议前端带 `text/event-stream, application/json`。
6. **未迁移 CORS**（T10 遗留）：直连 8080 前需补 `CorsConfigurationSource` + `HttpSecurity.cors()`。
7. **历史无时间窗口**：旧改写层有 `context-max-age-minutes: 30` 的话题闸门；本实现按"最近 N 轮"注入，隔天追问也会带上旧上下文。如需时间窗口，需在 `recentTurns` 上追加过滤（配置项一并补）。
8. **失败轮次不进历史**：失败/空回答轮次被跳过（§5.8），追问"换种问法再试试"拿不到失败上下文。如需，可改为注入带占位回答的轮次。
9. **会话无 TTL / 条数上限**：单用户会话数不设限（列表截断 50、轮次截断 100 只在读侧）；长期运行需评估清理策略。
10. **测试痕迹**：本任务新增用例的会话与轮次都在用例内清理（`@AfterEach`）；但 T9 既有 `ChatControllerIntegrationTest` 的两例（未改其清理逻辑）现在会各自**自动新建 1 个会话 + 落 1 轮**且不清理——每跑一次全量构建在本机库留下 2 个会话 + 2 条轮次（实测 id=135/136 与 147/148，标题即用例问题）。如需干净，可在该用例补 `@AfterEach` 清理，或手工删除；另每次运行仍新增随机测试用户（`t11s*` / `t11m*` / `t10c*`）与审计记录（与 T10 同口径，未加自动清理）。
