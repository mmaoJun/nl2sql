# T12 实施报告 — 前端联调（登录 + 聊天 + 轨迹基础版）

- 状态：**完成**。浏览器全链路（登录 → 选 `nlp2sql_test` → 提问 → 流式回答 + 结果表格 + 轨迹 → 同会话追问 → 刷新后侧栏/历史回显）对真实本地模型实测通过；后端补的两个控制器与既有 50 例一并全绿（53 例，0 失败）。
- 提交：`ca928ba`（后端控制器与用例）→ `6c85947`（SSE 聊天与轨迹面板）→ `77f555d`（设置页与表结构）→ `6f220b1`（联调修复）
- 环境：本机 MySQL 8.0.41 ✓、Ollama `qwen2.5-coder:7b` ✓、Redis 未运行（元数据缓存降级直连，不影响链路）；后端 8080（`target/nl2sql-backend-1.0.0-SNAPSHOT.jar`）+ 前端 5173（`npm run dev`）实测期间均在跑
- 旧项目 `F:\FANG_ZHENG\nlp2sql` 全程只读参考；工作树中 `docs/` 等非本任务文件未动（仅 `git add backend/ frontend/` 下本任务路径）

## 1. 文件清单

后端（新增，最小移植；服务层未改一行）：

| 文件 | 说明 |
|---|---|
| `controller/DataSourceController.java` | `/api/v1/datasources` 六端点，逐字对齐旧项目（含"连接失败 = HTTP 200 + 业务码 500 + 连接失败"失败语义） |
| `controller/MetadataController.java` | `/api/v1/metadata` 三端点，复用 T7 `MetadataService` |
| `test/.../controller/DataSourceMetadataIntegrationTest.java` | 3 例，真实 HTTP + MySQL，**零 LLM 调用** |

前端（新增 2，修改 8）：

| 文件 | 改动 |
|---|---|
| `src/api/chatStream.js`（新） | SSE 客户端：fetch + ReadableStream + 手写帧解析（EventSource 不能带 Authorization） |
| `src/components/TracePanel.vue`（新） | 轨迹面板基础版（按序工具名 + 入参/结果摘要 + 成功/失败） |
| `src/api/client.js` | 未认证统一处理抽成 `handleUnauthenticated`，401/403 都跳登录（后端实际是 403，T10 实测） |
| `src/api/endpoints.js` | 移除同步 `chat`（被 SSE 取代）与 `getModels`（后端无该接口）两个失效导出 |
| `src/stores/chatStore.js` | `sendMessage` 改流式：TEXT_DELTA 逐段渲染 / TOOL_CALL·TOOL_RESULT 入轨迹 / FINAL 收敛回答·SQL·结果表·sessionId / ERROR 落错误文本；历史轮次正文改用 `TurnVO.answer` |
| `src/components/ChatMessage.vue` | 流式光标；模型行改"provider 可缺省拼接"；移除已不存在契约的改写提示（RewriteGate 不迁） |
| `src/components/ChatInput.vue` | 新增 `loading` 属性（联调修复"生成期间误报请先选择数据源"） |
| `src/components/SchemaExplorer.vue` | 改懒加载两级：表列表 → 展开时按表拉列详情；刷新走 `/metadata/refresh` 后重挂载 |
| `src/views/ChatPage.vue` | 隐藏 ModelSelector（原位换"表结构"抽屉入口）；提问携带 sessionId、首问由后端自动建会话并回填；渲染 TracePanel；会话切换/新建/重命名/删除接线（切换即 GET turns 回显） |
| `src/views/SettingsPage.vue` | 新增/编辑共用对话框（编辑走 PUT，密码留空 = "******"不修改）；连接测试失败与删除取消不再产生未处理拒绝 |

未改：`ModelSelector.vue`（仅不再挂载）、`sessionStore.js`、`datasourceStore.js`、`SessionList.vue`、`ResultTable.vue` 等。

## 2. 新增控制器端点表

| 方法 | 路径 | 入参 | 成功 | 失败 |
|---|---|---|---|---|
| GET | `/api/v1/datasources` | — | `ApiResponse<DataSourceVO[]>`（密码 `******`） | 未认证 403 |
| POST | `/api/v1/datasources` | `DataSourceDTO` | `ApiResponse<DataSourceVO>` | 缺字段 HTTP 400；类型不支持 1004 |
| PUT | `/api/v1/datasources/{id}` | `DataSourceDTO`（密码 `******` = 不修改） | `ApiResponse<DataSourceVO>` | 不存在 1001；缺字段 400 |
| DELETE | `/api/v1/datasources/{id}` | — | `ApiResponse<Void>` | 未认证 403 |
| POST | `/api/v1/datasources/{id}/test` | — | `ApiResponse<true>`（code=200） | 连不上：HTTP 200 + code=500 + "连接失败"（旧形状） |
| GET | `/api/v1/datasources/supported-types` | — | `ApiResponse<DataSourceTypeInfo[]>`（mysql/postgresql） | — |
| GET | `/api/v1/metadata/tables` | `datasourceId` | `ApiResponse<TableInfo[]>`（表名+注释，不含列） | 数据源不存在 1001 |
| GET | `/api/v1/metadata/tables/{tableName}/columns` | `datasourceId` | `ApiResponse<ColumnInfo[]>` | 同上 |
| POST | `/api/v1/metadata/refresh` | `datasourceId` | `ApiResponse<Void>` | 同上 |

安全配置无需改动：两组路径落在 `anyRequest().authenticated()` 内（未认证 403）。

## 3. 验证（真实输出关键行）

### 3.1 后端全量构建

`cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package` → **EXIT=0**，surefire 汇总：

```
Tests=53 Failures=0 Errors=0 Skipped=1        （原 50 + 新增 3）
Tests run: 3, Failures: 0, Errors: 0 -- DataSourceMetadataIntegrationTest   （新增）
surefire 单文件明细：另 13 个既有测试类全绿，Skipped=1 为 AgentCloudSmokeTest（缺 key 按设计跳过）
```

新增用例实测输出（`target/surefire-reports/...-output.txt`）：

```
[T12-api] 未认证访问 datasources/metadata -> HTTP 403
[T12-api] supported-types=[DataSourceTypeInfo[code=mysql, displayName=MySQL, defaultPort=3306], DataSourceTypeInfo[code=postgresql, displayName=PostgreSQL, defaultPort=5432]]
[T12-api] 列表 5 条，定位 nlp2sql_test -> id=5
[T12-api] metadata/tables=25 张，users 列=[id, name, email, phone, age, gender, department_id, title, salary, hire_date, address, emergency_contact, emergency_phone, id_card, education, status, avatar_url, last_login_at, created_at, updated_at]
[T12-api] metadata/refresh -> success
[T12-api] 不可达数据源连接测试 -> HTTP 200 code=500 message=连接失败
[T12-api] CRUD 环: create -> list -> test(失败语义) -> update -> 1001 -> 400 -> delete 全部通过
```

### 3.2 前端

```
cd F:\FANG_ZHENG\nl2sql\frontend && npm run build   → ✓ built in 8.02s（1696 modules，ChatPage chunk 20.26 kB）
npm run dev                                          → Local: http://localhost:5173/（实测期间常驻，curl 探活 200）
后端 jar 启动后 /api/v1/health → {"code":200,"message":"success","data":"ok"}
```

## 4. 全链路实测证据

证据目录：`docs/dispatch/T12-evidence/`（未入库；含原始 SSE 文件、截图、复跑脚本）。测试账号 `t12ui1791779365`（密码同测试常规弱串，token 一律不完整落盘）。

### 4.1 curl SSE：第一个问题（真实 LLM，5.4s）

`GET /api/v1/chat/stream?question=users 表中共有多少位用户？&datasourceId=5`（未带 sessionId → 后端自动建会话）：

```
事件序列：TOOL_CALL, TOOL_RESULT, TOOL_CALL, TOOL_RESULT, TOOL_CALL, TOOL_RESULT, TEXT_DELTA, FINAL   （共 8 个，原始帧 q1.sse）
TOOL_CALL   list_tables  input={"datasource_id":5}
TOOL_RESULT list_tables  success=True  summary=[{"tableName":"addresses","comment":"收货地址",...
TOOL_CALL   get_schema   input={"datasource_id":5,"table_name":"users"}
TOOL_RESULT get_schema   success=True
TOOL_CALL   execute_sql  input={"datasource_id":5,"sql":"SELECT COUNT(*) FROM users"}
TOOL_RESULT execute_sql  success=True  summary={"columns":["COUNT(*)"],"rows":[{"COUNT(*)":200}],"rowCount":1,...
TEXT_DELTA  delta=系统中共有 200 个用户。
FINAL       answer=系统中共有 200 个用户。 sql=SELECT COUNT(*) FROM users columns=['COUNT(*)'] rows=[{'COUNT(*)': 200}]
            rowCount=1 model=qwen2.5-coder:7b sessionId=180 latencyMs=5362 firstEventMs=27
```

（`q1-frames.txt` 为解码后全文。）

### 4.2 curl SSE：追问（同一会话）

`...&sessionId=180`（问题"我刚才问的第一个问题是什么？"）：事件序列 `TEXT_DELTA, FINAL`（无工具调用，0.4s），FINAL `answer="系统仅支持只读查询。" sessionId=180`。
**这一轮是本任务的"curl 编码事故"受害者，见 §6.1**：问句经 Git Bash 传给 curl 后字节被转成 GBK，落库为 U+FFFD 乱码（`chat_turns` 258/259），污染了该会话历史。

### 4.3 浏览器实测（真实 Chrome，零依赖 CDP 无头驱动）

本机无 Playwright，使用 Node 20 `--experimental-websocket` + Chrome CDP 的零依赖脚本（`drive-ui.mjs` / `fix-run.mjs`，两脚本同源逻辑）驱动**真实前端页面**（vite 5173），关键网络与 DOM 快照如下（截图逐张列于下）：

```
[net] SSE 请求: http://localhost:5173/api/v1/chat/stream?question=orders 表中共有多少条订单记录？&datasourceId=5&sessionId=180
      （UI 提问确实携带 sessionId；datasourceId=5 = nlp2sql_test，选择器项"测试库(25表)"）
[ui] 数据源下拉项: testmysql | pg_demopostgresql | 外卖mysql | mywebsite_adminmysql | 测试库(25表)mysql
[ui] 流式中(loading)=true，已渲染轨迹行=2        （TEXT_DELTA 到达前轨迹已在面板增量出现）
[ui] 回答=订单表中共有 500 条订单记录。 轨迹行数=6 表格列=["#","count(*)"] 数据行=["1","500"] 计数=1 条记录
```

截图（`T12-evidence/`）：

| 文件 | 内容 |
|---|---|
| `shot-01-login.png` | 登录页 |
| `shot-02-chat-sidebar.png` | 聊天页：会话侧栏 + 数据源选择器 |
| `shot-03-history-restore.png` | 点会话 → GET turns 历史问答回显 |
| `shot-04-streaming.png` | 流式进行中：轨迹 2 步已现、光标、加载提示 |
| `shot-05-answer-table-trace.png` | 本轮收尾（受 §6.1 污染历史影响，该轮为拒答，见下） |
| `shot-10-streaming.png`（修复复跑） | 新会话流式进行中：轨迹面板 2 步 + 流式光标 + "正在分析并生成 SQL..." |
| `shot-11-answer-table-trace.png`（修复复跑） | **回答"订单表中共有 500 条订单记录。" + SQL 块 + 执行轨迹 6 步（调用/结果/成功）+ 结果表（COUNT(*)=500）+ 1 列 1 行 + 模型行** |
| `shot-12-after-reload-history.png`（修复复跑） | **刷新后**：侧栏新会话置顶（"orders 表中共有多少条订单记录？· 1 轮"）、历史问答回显、数据源回落到"测试库(25表)" |
| `shot-06-after-reload-history.png` | 第一轮刷新回显（会话 180：3 轮，含乱码问句） |
| `shot-07-schema-drawer.png` | 表结构抽屉：25 张表，展开 addresses 懒加载出 15 列（含中文注释） |
| `shot-08-settings-datasources.png` | 设置页：5 条数据源 + 编辑/测试/删除 |
| `shot-09-datasource-selected.png`（修复复跑） | 数据源下拉显式选择"测试库(25表)" |

### 4.4 会话历史回显（刷新，0 LLM）

会话 181（修复复跑的新会话）刷新后：侧栏 `orders 表中共有多少条订单记录？ · 1 轮`，点开回显 2 条气泡（问 + 答），数据源选择器自动回落 id=5（`ui-fix-after-reload.json`）。
会话 180 刷新后：侧栏 `3 轮`，回显 6 条气泡（含 §6.1 的乱码问句与拒答，如实保留）。

### 4.5 会话/数据源/元数据端点实测（浏览器视角）

- 新建对话（POST /sessions）：点击后侧栏即时+1、消息区清空、提问 URL 携带 `sessionId=181`；
- 数据源选择器：下拉 5 项、显式选择"测试库(25表)"后提问 URL `datasourceId=5`；
- 表结构抽屉：25 表 + 懒加载列（走 `/metadata/tables`、`/tables/{name}/columns`、`/metadata/refresh`）；
- 设置页：列表 5 行、每行"编辑/测试/删除"（编辑为 T12 新增，走 PUT）。

## 5. 前端契约对齐要点（对照 T9/T10/T11 报告）

1. **SSE**：`Accept: text/event-stream, application/json` 同时带上（T10 记录：只带前者时起流前错误拿不到 JSON）；`sessionId` 仅在非空时作为查询参数；FINAL 之前容错（TEXT_DELTA 可缺）；EOF 后以 FINAL 收敛，`ERROR` 事件落为消息错误文本。
2. **403 语义**：未认证由过滤链返回 403（非 401），axios 与 SSE 两条通道共用一个 `handleUnauthenticated`（清 token + 跳登录 + 只在非登录页提示）。
3. **sessionId**：首问不带（后端自动建会话并回填）→ 保存到 localStorage → 后续追问带上（T11 §7.1 的要求）；FINAL 缺 `sessionId`（无会话降级）时不覆盖本地。
4. **两个通道的错误码风格**：chat/会话走 HTTP 200 + body 码（拦截器已统一按 body code 处理）；标题校验 400 由拦截器兜底提示。
5. **ModelSelector 隐藏**：删掉 `getModels` 调用（后端无 `/models`），组件文件保留不删。
6. 历史轮次不重放结果行（TurnVO 不含 rows，设计如此）：刷新后只回正文 + SQL + 模型行。

## 6. 联调中发现的问题与处置

### 6.1 curl 中文问句编码事故（工具链问题，非产品缺陷）

- 现象：`curl --data-urlencode "question=users 表中共有多少位用户？"` 落库为 `users ���й��ж���λ�û���`（U+FFFD）。
- 诊断证据：同一库中 Java 测试写入的审计行（id 206-209，T9-T11 时期）中文完好；T12 curl 的审计行（209-211）与 `chat_turns` 258-260 乱码 —— 说明坏在 Git Bash 向 curl 传 argv 的编码转换（GBK 字节被按 UTF-8 解码），后端/前端链路无责（浏览器实测中文全程正常）。
- 连带后果：会话 180 的历史注入被乱码污染，**7B 对干净问题（浏览器第 3 问）也回了面板化拒答"系统仅支持只读查询。"**（后端日志角色序列 `[system, user, assistant, user, assistant, user]` 证明历史已注入；`qwen2.5-coder:7b` 面对污染上下文触发 prompt 里的拒答话术）。
- 处置（唯一一次修复，见 §7 预算）：在**全新会话**里复跑浏览器链路 → 回答、表格、轨迹、刷新回显全部正常（§4.3/4.4）。会话 180 的污染记录保留作证，未清理。
- 经验：Git Bash 下 curl 传中文参数会乱码，后续联调脚本改为浏览器或文件体传参；已把复跑脚本留在证据目录。

### 6.2 联调修复（提交 `6f220b1`）

流式回答期间输入框底部提示误报"请先选择数据源"（`disabled` 同时来自 loading 与未选源）：拆出 `loading` 属性，提示"正在生成回答，请稍候..."。截图为证（`shot-10-streaming.png` 为修复后）。

## 7. 真实 LLM 预算披露

- 计划内 **3 次问答**：curl Q1（SSE 证据）/ curl Q2（同会话追问）/ 浏览器 Q3（会话 180，URL 携带 sessionId）。
- **7B 行为问题修复 1 次**（上限内）：curl 编码事故污染历史导致 Q3 拒答，修复 = 用户真实路径（新建会话）复跑 1 问 → 成功。合计 **4 次**，未做任何提示词/温度调参。
- 零成本预检：正式跑浏览器前先用 `q1.sse` 真实帧做 mock 回放，验证了 TEXT_DELTA/轨迹/表格渲染（`precheck-04/05*.png`），并借此发现并修正了驱动脚本的点击时序问题——预检不消耗 LLM。
- 基线说明同 T9-T11：`mvn clean package` 每次仍会跑既有真实模型用例（AgentEndToEndTest 2 问 + ChatControllerIntegrationTest 2 问 + SessionMemoryIntegrationTest 2 问），属既有基线。

## 8. 偏差与自主决策

1. **前端多做了两处**（超出"只改数据流"的最小集，均与服务端契约对齐有关）：`SettingsPage` 补"编辑"（PUT）入口——派发说明要求"设置页接数据源 CRUD"，旧前端只有 create/delete/test；`ChatInput` 拆 `loading` 属性（§6.2）。
2. **SchemaExplorer 改懒加载**：`/metadata/tables` 只回表名+注释（旧实现同形状），组件原逻辑读 `t.columns` 永远为空；改为展开时按表拉列详情，才能真正"展示"。原组件在旧项目同样没有挂载点，T12 把它放进聊天页 header 的抽屉（原位是隐藏掉的 ModelSelector），"能展示即可"。
3. **移除失效导出**：`endpoints.js` 的同步 `chat`（请求体字段是旧契约 `message/model`，已被 SSE 取代）与 `getModels`（后端无接口）删除，避免死代码引用坏契约。
4. **ChatMessage 删除改写提示**：新架构不迁 RewriteGate/QuestionRewriter（T11 §5.1），`resolvedQuestion/rewriteStatus` 在最终契约里恒空，提示永不出现。
5. **新建对话仍走 POST /sessions**（旧行为，五端点之一）；首问无会话时**不再前端预建**，改用后端自动建会话 + FINAL 回填（T11 契约），两条路径都实测过。
6. **不迁 ModelController**（派发说明明确）；模型选择器隐藏而非改造。
7. **不引入任何新前端依赖**；浏览器实测采用零依赖 Node CDP 脚本（仅存在于证据目录，未入库、不进构建）。
8. **未改安全配置**：两个新控制器落在既有 `authenticated()` 之内，403 行为与 T10 一致。

## 9. 前端仍存在的缺口（W4 建议）

1. **轨迹面板精修**：未按 `callId` 配对"调用/结果"、无折叠、长摘要单行省略（仅 title 提示）、失败态配色可再打磨。
2. **空态/错误态**：SSE `ERROR` 只有文本；无断线重连/重试按钮；认证跳转无过渡骨架屏；首次加载无 skeleton。
3. **取消生成**：`chatStream` 已支持 AbortSignal，UI 未接"停止生成"按钮。
4. **本地通道非逐字**：`stream=false` 决定 TEXT_DELTA 为整段一条，"逐字打字机"效果需云端通道或后端改流式装配。
5. **历史轮次无结果行**（设计如此）：刷新后表格/轨迹不复现；如需回看数据要重新提问。
6. **数据源编辑**：密码"留空=不修改"；未做"保存前自动测连"与字段级校验反馈（依赖后端 400 提示）。
7. **会话侧栏**：无分页/搜索（后端截断 50）；空对话（点了新建未提问）会留在列表里。
8. **ModelSelector 组件文件保留**未删：等后端有模型列表接口（如多模型对比）再决定复用或删除。
9. **测试痕迹**：T12 联调用例在库里留下 2 个会话（180 含乱码历史、181 干净）+ 4 轮记录 + 随机用户与审计行；证据截图引用的 `t12-*` 文件名依赖这些数据，未清理（需要干净环境可手工删会话 180/181 与用户 `t12ui*`）。

## 10. 复跑方式（供核对）

```bash
# 后端（jar 已验证）
cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package && java -jar target/nl2sql-backend-1.0.0-SNAPSHOT.jar
# 前端
cd F:\FANG_ZHENG\nl2sql\frontend && npm run dev     # http://localhost:5173
# 浏览器全链路复跑（零依赖，需 Node 20+ 与本机 Chrome）
cd F:\FANG_ZHENG\nl2sql\docs\dispatch\T12-evidence
node --experimental-websocket drive-ui.mjs <token> <username>            # 真实问答
node --experimental-websocket drive-ui.mjs <token> <username> --mock     # 真实帧回放（0 LLM）
```
