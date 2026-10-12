# T12 派发说明 — 前端联调（登录 + 聊天 + 轨迹基础版）

你是实现子代理，只依据本文件执行。完成后写报告到 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T12-report.md`。

**必读（前序契约，其他计划文档不要读）**：
- `docs/dispatch/T9-report.md` §2（聊天接口与 SSE 事件契约）
- `docs/dispatch/T10-report.md`（认证：403 语义、放行规则）
- `docs/dispatch/T11-report.md`（契约增量：`ChatResult.sessionId`、sessions 五端点、turns 端点）

## 背景与目标

后端已完成：聊天同步 + SSE（T9）、JWT 认证（T10）、会话记忆（T11）。前端骨架已移植（T6，`F:\FANG_ZHENG\nl2sql\frontend`，Vue3 + Element Plus）。本任务是 W3 的收官：**浏览器里完成"登录 → 选数据源 → 提问 → 流式回答 + 结果表格 + 轨迹 → 追问 → 会话侧栏"全链路**。

## 交付物

1. **后端补两个控制器**（服务层已存在，属最小移植；契约对齐旧项目以便前端组件直接可用）：
   - `DataSourceController`（`/api/v1/datasources`）：GET 列表 / POST 创建 / PUT `{id}` / DELETE `{id}` / POST `{id}/test` / GET `supported-types`（旧项目同路径同形状；DTO/VO 用 T3 已迁的 `DataSourceDTO/DataSourceVO/DataSourceTypeInfo`）
   - `MetadataController`（`/api/v1/metadata`）：GET `tables?datasourceId=` / GET `tables/{tableName}/columns?datasourceId=` / POST `refresh?datasourceId=`（用 T7 的 `MetadataService`）
   - 不迁 `ModelController`（新架构模型由 `nl2sql.model.provider` 配置，无按请求切换）
2. **前端对接**（修改 `frontend/`，不重做设计，只改数据流与契约对齐）：
   - `api/client.js`：未认证（**403**，非 401）统一跳登录；保留 `/api` baseURL
   - 聊天走 **SSE（fetch stream + `Authorization` 头 + `Accept: text/event-stream`）**，不用 EventSource（其不支持自定义头）：`TEXT_DELTA` 逐段渲染、`TOOL_CALL/TOOL_RESULT` 进轨迹面板、`FINAL` 出回答 + `ResultTable`（columns/rows）+ SQL + `sessionId`；`ERROR` 显示错误提示
   - 轨迹面板**基础版**：按序展示工具名 + 入参摘要 + 结果摘要（含成功/失败），样式沿用现有组件风格（精修留 W4）
   - 会话侧栏：sessions 五端点接入（新建/切换/重命名/删除；切换时 GET `{id}/turns` 渲染历史问答）；提问携带 `sessionId`
   - 数据源选择器接 `/api/v1/datasources`；设置页接数据源 CRUD + 连接测试；SchemaExplorer 接 metadata 端点（能展示即可）
   - `ModelSelector` 隐藏（后端无模型列表接口）
3. **验收（必须实际执行）**：
   - `npm run build` 通过；`npm run dev` 起服务
   - 后端起服务（MySQL + Ollama 已在本机运行），**真实全链路**：登录 → 选 `nlp2sql_test` 数据源 → 提一个问题 → 看到流式回答 + 表格 + 轨迹 → 追问一次 → 刷新后会话侧栏能看到历史。用 curl 验证 SSE 事件流 + 浏览器工具（如可用）或页面交互实测，报告给证据（事件日志/截图路径）
   - **真实 LLM 预算：≤3 次问答**；7B 行为问题最多修 1 次，不无限调参
4. 不改后端 T2-T11 既有行为（新增控制器除外）；前端只改必要文件，报告列清单。

## 硬约束

- CORS 不需要（vite 代理同源）；不引入新前端依赖（除已有依赖外，如需 EventSource polyfill 类一律不加）。
- git：只 `git add backend/ frontend/` 本任务路径；**禁止 `git add -A` / `git add .`**；中文 conventional commit，3–4 个提交（后端控制器 / 前端 api 与聊天 / 会话侧栏与设置 / 联调修复）。
- 工作树中非本任务文件（docs/ 等）不要动。

## 验证（实际执行，真实输出进报告）

1. `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package` 通过（50 例 + 新增控制器用例，如有）。
2. `cd F:\FANG_ZHENG\nl2sql\frontend && npm run build` 通过；`npm run dev` 可打开。
3. 全链路实测证据：SSE 事件序列、最终回答与表格数据摘要、追问结果、会话历史回显。

## 报告要求（写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T12-report.md`）

- 文件清单（后端/前端分组）；新增控制器端点表
- 验证真实输出关键行；全链路实测证据
- 前端仍存在的缺口清单（W4：轨迹精修、空态/错误态打磨等）
- 提交 hash；遗留问题
