# T26 实施报告 — 前端体验打磨（轨迹精修 / 错误态 / 停止生成）

- 状态：**两项前端打磨完成并提交**（轨迹面板精修、停止生成 / 错误重试 / 认证跳转 / 骨架屏）。
- 提交：`5640602`（轨迹面板配对与折叠）→ `643c7b6`（停止生成 / 错误重试 / 骨架屏 / 登录回跳）→ `9f88099`（停止后刷新会话侧栏）。
- **验收项 3.2「真实全链路实测」未能执行**：任务期间后端 8080 进程退出且仓库中不存在可运行的 jar 产物，权限层拒绝在 `backend/` 下构建/启动（被拒命令见 §5.1）；真实 LLM 消耗 **0/3 次**。已用「真实浏览器 + 真实前端代码 + 网络层 mock（REST 由 CDP 拦截、SSE 用 T12 实拍帧回放）」覆盖全部交互场景作为替代证据，并如实标注（§4.2）。原计划脚本 `drive-t26.mjs` 已写好，后端恢复后可一键复跑。

## 1. 文件清单（仅 frontend/，共 8 个文件）

| 文件 | 变更 |
|---|---|
| `frontend/src/components/TracePanel.vue` | 重写：按 `callId` 配对、折叠/展开、摘要单行省略 + `title`、失败态 danger 配色 |
| `frontend/src/components/ChatInput.vue` | `loading` 时显示"停止"按钮（abort），停止后提示"已停止生成"（4s） |
| `frontend/src/components/ChatMessage.vue` | 错误气泡（后端 message + "重试"按钮）、"已停止生成"标记、空内容不再渲染空气泡 |
| `frontend/src/stores/chatStore.js` | `runStream` 核心抽出 + `AbortController`（`stopGeneration`）、`retryMessage`、`ERROR` 事件拆出 `errorMessage`、`cancelled` 轮次回显标记 |
| `frontend/src/views/ChatPage.vue` | 停止/重试接线、`restoring` 骨架屏、停止后刷新会话列表 |
| `frontend/src/api/client.js` | 403 未认证：轻提示（warning toast）+ 跳登录带 `redirect` 原路由（`chatStream` 通道共用） |
| `frontend/src/router/index.js` | 守卫跳登录携带 `redirect` |
| `frontend/src/views/LoginPage.vue` | 登录成功回跳 `redirect`（仅接受站内绝对路径，防开放重定向） |

证据目录 `docs/dispatch/T26-evidence/`：`drive-t26.mjs`（真实链路脚本，未执行）、`verify-ui-mock.mjs`（替代验证脚本，可复跑）、`t26-results.json` / `t26-mock-results.json`（断言）、`t26-precheck-stdout.txt`、`t26-mock-stdout.txt`、`t26-build.log`、`t26-dev-server.log` 与 11 张截图。

## 2. 项 1：轨迹面板精修

实现（`TracePanel.vue`）：

- **callId 配对**：`TOOL_CALL`/`TOOL_RESULT` 同 `callId` 合并为一条（工具名 + 入参摘要 + 结果摘要 + 成功/失败/进行中）；结果未到时显示"进行中"（info），配不到调用的孤儿结果单独成条并标"未配到调用"（warning）；头部计数由"步"改为"N 次调用"。
- **折叠/展开**：默认折叠（摘要行常显，全文点击展开为美化 JSON 的 `pre`，可再次收起；`trace` 被整体替换时清空展开态防串行）。
- **摘要省略**：`.trace-text` `white-space: nowrap + ellipsis`，`title` 悬浮全文（入参/结果各自独立 title）。
- **失败配色**：Element Plus danger 语义变量（`--el-color-danger` / `-light-9` 背景 + 左边框），与"进行中"（info）"成功"（success）区分。

证据（`t26-mock-stdout.txt` / `t26-mock-results.json`；截图 `T26-M4a/M4b/M4c`）：

- 10 帧（T12 实拍帧 + 1 组合成失败帧）回放后：`条目数(配对后)=4`，steps = `list_tables/成功, get_schema/成功, execute_sql/成功, execute_sql/失败`；
- `摘要均单行省略(nowrap)=true`、每条入参/结果均带 `title`、默认均折叠；
- 失败条边框色与失败状态色 = `rgb(245, 108, 108)`（Element Plus danger `#f56c6c`）；
- 流式中（截帧 `T26-M4a`）：`list_tables/成功, get_schema/进行中`（配对 + 进行中态）；
- 展开：`展开态=true，全文块数=2`（入参/结果两块，入参 `{ "datasource_id": 5 }` 已美化），再次点击收起成功。

## 3. 项 2：空态 / 错误态 / 停止生成

交互闭环（UI 状态说明）：

| 场景 | 触发 | UI 表现（实测断言） |
|---|---|---|
| 停止生成 | `loading` 时输入区右侧按钮切换为"停止"（方块图标），点击 → `chatStore.stopGeneration()` abort | `loading` 复位 ✓、停止按钮消失且发送按钮恢复 ✓、**残留流式光标 = 0** ✓、助手气泡出现"已停止生成"（warning 标记）✓、输入区提示"已停止生成"（4s）✓、mock 侧 abort 计数 = 1 ✓、之后 2.5s 轨迹条目数不再增长（流已冻结）✓（截图 `T26-M5a/M5b`） |
| SSE ERROR / 起流前错误 | `errorMessage` 落到消息上（`content` 保留已渲染的部分回答，不覆盖） | 错误气泡内展示后端 `message` + "重试"按钮；`loading` 已复位、发送按钮恢复 ✓（截图 `T26-M6a`） |
| 重试 | 点"重试" → `retryMessage`：移除失败气泡，用 **同一问题 / 同一数据源 / 同一会话** 重发（不重复追问行） | 请求 URL 实证：失败与重试两次均为 `question=查询所有用户&datasourceId=5&sessionId=902`；重试成功后错误气泡移除 ✓、用户消息仅 1 条 ✓（截图 `T26-M6b`） |
| 会话刷新 | 后端把停止轮次落为 `cancelled`（T25） | 重新加载时该轮 answer 为空 → 显示"已停止生成"标记（`loadFromTurns` 识别 `status==='cancelled'`，断言 `[2] 历史回显` 含 "已停止生成"） |
| 认证跳转 | 403（伪造 token 发问） | 轻提示 toast「登录已过期，请重新登录」+ 跳转 `URL=/login?redirect=/chat` ✓（截图 `T26-M7`）；带 `redirect` 进登录页时，登录成功落到原路由 `/settings` ✓（截图 `T26-M1b`）；未登录访问 `/settings` 守卫跳 `/login?redirect=/settings`（真实 dev server 上验证，`T26-A1` + `t26-precheck-stdout.txt`） |
| 骨架屏 | 首屏 / 切会话的 `restoring`（turns 延迟 1200ms 复现） | `el-skeleton` 可见（8 行）✓（截图 `T26-M2`），加载完成后正常渲染历史（含 cancelled 轮次标记） |

## 4. 项 3：验收

### 4.1 build / dev

- `cd F:\FANG_ZHENG\nl2sql\frontend && npm run build` → `✓ built in 8.98s`（另有既有 chunk >500kB 警告，非本任务引入；日志 `t26-build.log`）。
- `npm run dev`：可打开。**注意**：原 5173 实例（只监听 IPv6 `[::1]`，随后进程退出）在任务期间消失，已以 `npm run dev -- --host 127.0.0.1 --port 5173` 重启（PID 32276，`t26-dev-server.log`），浏览器实测全部基于该实例。

### 4.2 全链路实测（**未能执行，如实记录**）

- 计划场景：登录 → 提问 → 中途停止 → UI 收尾 + 会话轮次 `cancelled` 后端核对；正常问答看轨迹新样式；构造可见 `ERROR` → 重试。
- 受阻事实：任务开始时四端服务均在（实测 netstat 8080/3100/11434/3306 均监听），约 17:06 后端 8080 的 java 进程整体退出；`backend/target` 下无 jar 产物；尝试 `mvn -Dmaven.test.skip=true package` 被权限层以"只改 frontend/，绝不动后端"为由拒绝。
- 替代验证（**非真实后端**，消耗 0 次 LLM）：`verify-ui-mock.mjs` — 真实 Chrome + 真实前端代码，REST 经 CDP `Fetch.fulfillRequest` 返回 mock（登录/数据源/会话/轮次），SSE 聊天流在页面内以 **T12 实拍帧**（`q1.sse`，仅改写 sessionId）+ 1 组合成失败帧回放，帧间隔可控。覆盖：登录表单、首屏骨架屏、历史回显（含 cancelled）、轨迹配对/折叠/失败配色/展开、流式进行中态、停止生成收尾、错误气泡 + 重试参数核对、403 toast + 跳转、redirect 回跳。
- 未覆盖（计划中的真实路径）：真实 SSE `ERROR` 事件、真实 4001/3003 响应、真实 `cancelled` 落库核对、真实 LLM 输出质量。
- 复跑方式（后端恢复后）：`cd F:\FANG_ZHENG\nl2sql\docs\dispatch\T26-evidence && node --experimental-websocket drive-t26.mjs`（内含停止场景轮询 `/sessions/{id}/turns` 直到 `last.status==='cancelled'` 的后端侧核对与截图）。

### 4.3 真实 LLM 预算

- 消耗 **0/3 次**（全链路未能对真实后端发起问答；mock 回放不产生模型调用）。

## 5. 偏差与自主决策

1. **真实 E2E 缺失**（最大偏差）：环境后端不可用 + 权限层阻止在 backend/ 下构建启动，未强行绕过；以真实浏览器 + 网络层 mock 替代并明确标注，未把 mock 结果表述为真实链路结果。
2. **前端 dev server 自行重启**：原实例已死（非本任务所杀），以 IPv4 绑定重启同端口；Chrome 无法访问原 `[::1]` 实例（实测 `ERR_CONNECTION_REFUSED`）也是重启原因。
3. 验收脚本保留两份：`drive-t26.mjs`（真实链路，未跑）+ `verify-ui-mock.mjs`（已跑，可复跑）。
4. 失败态配色的截图含 **1 组合成失败帧**（结构同真帧：`success:false` + 失败 summary），本报告已注明；真实失败路径的呈现未经真机验证。
5. 停止后调用 `sessionStore.fetchSessions()`（提交 3）：让"首问即被停止"的新会话立刻出现在侧栏（该场景前端拿不到 sessionId——只在 FINAL 回填）。
6. `TracePanel` 采用"摘要行常显 + 全文默认折叠"，未做"短内容自动展开"（口径：默认折叠长内容，短内容通过摘要行 + title 已完整可达）。
7. 未引入任何新前端依赖（`el-skeleton` / `el-tag` 语义色 / `@element-plus/icons-vue` 均为既有依赖）；未重做视觉设计。
8. 工作树中他人的未跟踪文件（`实习生课题(1).txt`）未触碰；`git add` 仅限本任务 frontend 路径（3 个提交共 8 个文件，`git diff 5ede539..HEAD --stat` 无 backend/ 变更）。报告与 evidence 按派发要求**未入库**。

## 6. 遗留问题

1. ~~真实全链路（含停止 → 后端 `cancelled` 核对）未跑~~ —— 已于 2026-10-12 主线补测通过（见 §7）。
2. 真实 SSE `ERROR` 事件路径仍未以故障注入方式真机触发（403 与停止路径已真机验证，ERROR 以真实帧回放验证）；无效数据源还暴露了 7B 重试 5 次的问题（见 §7 发现）。
3. 停止场景下若为本轮首问，前端 localSessionId 仍指向旧会话（FINAL 才回填 sessionId 的既有契约），侧栏可见 cancelled 会话但当前会话未被自动切到它。
4. 后端 8080 无产物、无启动脚本，环境恢复需主线重建 jar。
5. mock 验证无法替代真实 LLM 的轨迹数据量级与失败表现，轨迹面板在超长摘要/多轮工具链下的性能未压测。

## 7. 真实全链路复跑（主线补测，2026-10-12）

环境：主线重建后端 jar 并启动（8080）、前端 127.0.0.1:5173 在线，执行 `drive-t26.mjs`（真实浏览器 + 真实后端 + 真实本地 7B）。**LLM 实际用量 2 次问答（在 ≤3 预算内）**；证据更新于 `T26-evidence/t26-results.json` 与新增截图。

- **D 场景（停止生成）✓**：真实提问"查询年龄大于25岁的用户" → 流式中点"停止" → UI 收尾正确（loading=false、停止按钮消失、发送按钮恢复、残留流式光标 0、气泡与输入区均显示"已停止生成"）；**后端轮次核对 `status: "cancelled"`**（session 460 / turn 541，T25 联动生效）；刷新后历史显示"已停止生成"。（截图 T26-D1/D2/D3）
- **E 场景（错误态）部分 ✓ + 重要发现**：对**不存在的数据源（99999）**发问**未触发 SSE `ERROR`**——代理循环 5 次 `list_tables`（每次拿到"数据源不存在"类工具错误）后给出说明性最终回答（"…请确认数据源 ID 是否正确…"）。行为本身合理（前端无需错误气泡即可给出解释），但暴露一个问题：**无效数据源的工具报错没有提示"配置错误、重试无效"，7B 因此耗光 5 次迭代反复重试**——后续可为工具报错补该引导（列入 W4 收尾遗留）。真实 `ERROR` 事件的 UI 闭环仍以真实帧回放验证（§6 遗留 2），未做断层 Ollama 的故障注入（避免扰动演示环境）。
- **F 场景（403）✓**：伪造 token 发问 → 真实 403 → toast"登录已过期，请重新登录" + 跳转 `/login?redirect=/chat`。（截图 T26-F1）
- 遗留 3（首问即停止时 localSessionId 指向旧会话）与 5（轨迹性能压测）维持开放；遗留 4（后端产物/启动）已由主线解决（jar 重建 + 启动）。
