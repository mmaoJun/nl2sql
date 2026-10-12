# T26 派发说明 — 前端体验打磨（轨迹精修 / 错误态 / 停止生成）

你是实现子代理，只依据本文件执行，不读其他计划文档。完成后写报告到 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T26-report.md`。

## 背景

W4 收尾的前端部分。来源：T12 报告 §9 缺口清单第 1–3 项；后端已就绪（T25 新增轮次 `cancelled` 状态，与"停止生成"按钮联动）。契约参考：`docs/dispatch/T9-report.md` §2（SSE 事件）、T12 报告 §2（端点表）。

**先读**：`docs/dispatch/T12-report.md` §9（缺口原文）与 T25 报告（cancelled 状态）；只读这两份前序报告，不读其他计划文档。

## 交付项（逐项验收）

### 项 1：轨迹面板精修（`TracePanel.vue` 等）

- `callId` 配对"调用/结果"为一条（当前按序平铺两条）——展示为：工具名 + 入参摘要 + 结果摘要/成功失败态；
- 条目可折叠/展开（默认折叠长内容）；长摘要单行省略 + `title` 悬浮全文；
- 失败态配色（用 Element Plus 的 danger 语义色即可）；
- 保持现有组件风格，不引入新依赖。

### 项 2：空态 / 错误态 / 停止生成

- **停止生成按钮**：`chatStream` 已支持 `AbortSignal`——`ChatInput` 在 `loading` 时显示"停止"，点击 abort；停止后界面提示"已停止生成"（后端 T25 已把该轮记为 `cancelled`，会话刷新后可见该轮状态）。**注意**：abort 后要正确收尾 UI 状态（loading 复位、按钮恢复），不得残留半截流式光标。
- **SSE `ERROR` 展示与重试**：错误气泡内展示 `message` + "重试"按钮（重发同一问题，沿用同一会话）。
- **认证跳转体验**：403 跳登录前给一个轻提示（如 message toast），登录页回跳原路由（如简单可行）。
- 首屏/会话切换的轻量 skeleton（Element Plus `el-skeleton` 即可，避免大改）。

### 项 3：验收（必须实际执行）

1. `cd F:\FANG_ZHENG\nl2sql\frontend && npm run build` 通过；`npm run dev` 可打开。
2. **真实全链路实测**（后端 8080 / Ollama / mock-api 3100 / MySQL 均在跑；前端 5173 可能已在跑，先探测避免端口冲突）：
   - 登录 → 提问 → **中途点"停止"** → UI 正确收尾 →（可选：刷新会话列表核对该轮存在）
   - 正常问答一例 → 轨迹面板新样式正常（配对/折叠/配色）
   - 错误态：构造一次可见的 `ERROR`（如对不存在的数据源 id 发问，或断开 mock 后的 API 数据源提问——选择可行路径并如实记录）→ 重试按钮行为正确
   - 可用 `docs/dispatch/T12-evidence/drive-ui.mjs` 的思路驱动浏览器验证（该脚本可复用/改写入新的 evidence 目录），截图存 `docs/dispatch/T26-evidence/`
3. **真实 LLM 预算：≤3 次问答**（其一用于停止场景，其余用于轨迹与重试）；模型行为问题最多修 1 次，不无限调参。

## 硬约束

- **只改 `frontend/`**（及本任务报告/证据目录）；不动后端、不动 Hive/HDFS 容器与 mock 服务。
- 不引入新前端依赖（Element Plus 内建能力足够）；不重做视觉设计。
- git：只 `git add frontend/` 本任务路径；**禁止 `git add -A` / `git add .`**；中文 conventional commit，2–3 个提交。
- 工作树中非本任务文件不要动。

## 验证（真实输出进报告）

- build 输出关键行；三类场景的实测证据（UI 状态截图路径 / 关键交互日志）；停止场景附带一句后端侧核对（会话轮次状态为 cancelled）。

## 报告要求（写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T26-report.md`）

- 文件清单；三项分别的验证证据；UI 状态说明（停止/错误/重试的交互闭环）
- 偏差与自主决策；提交 hash；遗留问题
