# T27 派发说明 — 侧栏三段式改版（数据源 / 对话列表 / 用户信息）

你是实现子代理，只依据本文件执行。完成后把报告写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T27-report.md`。

## 参考图（可读）

用户提供的参考截图路径（用 Read 工具直接查看）：

`C:\Users\lsz25\.qoder-cn\tmp\F--FANG-ZHENG-nl2sql\images\a032fe01-f610-48c1-b379-dc0be75a7bee\055d4116-b272-49b8-9452-22834ff720be.png`

借用其**布局节奏与排版风格**（浅灰侧栏底、顶部圆角选择控件、分区小标题、紧凑列表行、底部用户行）；**不搬 AskTable 的品牌色/文案/资产**——主题色一律用现有 CSS token（`src/assets/main.css` 的 `--primary` 等）。

## 用户要求（原文）

"把左边栏做成类似图片中的样子，上方只保留一个数据源，中间是对话列表，最下方是登录信息和退出按钮"

## 现状（已侦察，可直接对照）

- `views/ChatPage.vue`：横向 flex = `SessionList`（左栏）+ `.chat-main`；`.chat-header` 内是 Connection 图标 + `DataSourceSelector` + "表结构"按钮。
- `components/SessionList.vue`：三段雏形——顶部 `new-session-btn`、中部 `session-items`、底部 `session-footer`（头像+用户名+设置 popover，退出登录藏在 popover 里）。
- `components/DataSourceSelector.vue`：el-select 封装（`v-model` + `data-sources` prop）。

## 交付项

1. **侧栏顶部 = 唯一控件：数据源选择器**。把 `DataSourceSelector` 从主区 header 移入侧栏最上、全宽浅底圆角（参照参考图顶部选择框），可加小前缀图标；绑定同一个 `selectedDsId`，**切换逻辑与 `restoreSession` 的"最后一轮数据源回填"行为不得改变**。顶部不得出现第二个控件。
2. **主区 header**：移除数据源选择器与装饰性 Connection 图标；"表结构"入口保留（留在 header，可轻量样式化），不新增控件。
3. **侧栏中部**：「新对话」按钮（保留现有事件与能力）+ 分区小标题「最近对话」（小号灰字，参照参考图）+ 会话列表（hover/选中/预览/改名/删除等现有行为全部保留）。
4. **侧栏底部**：头像 + 用户名（现有来源）+ **可见的「退出」按钮**（从 popover 提升；保留「设置」入口——小齿轮按钮紧邻）。退出行为与现状一致（清登录态 → /login）。popover 可移除或简化。
5. 视觉：行高紧凑（~36–44px）、分区间距一致、选中项高亮用 `--primary-bg`/`--primary` 系、hover 过渡；不重做整体主题、不动对话区。

## 硬约束

- 只改 `frontend/`；`DataSourceSelector` 对外接口不变；不引入新依赖、不重做主题。
- **前端 5173 是用户自己在跑的实例（监听 [::1]）：不要 kill / 重启它**——你的改动经 Vite HMR 自动生效。浏览器验证访问 `http://localhost:5173`。后端 8080 由主线启动、正常运行；MySQL 可用（数据源选"测试库(25表)"）；Docker 容器已停，不影响本任务。
- git：只 `git add frontend/` 本任务路径；**禁止 `git add -A` / `git add .`**；中文 conventional commit，2–3 个分批提交（先布局、再样式微调）。
- 工作树中非本任务文件不要动；报告与截图写 `docs/dispatch/T27-*`（归档提交由主线处理）。

## 验收（实际执行）

1. `cd F:\FANG_ZHENG\nl2sql\frontend && npm run build` 通过。
2. 浏览器实测：三段布局符合要求；**切数据源后提 1 个真实问题**（如"查询所有用户"）回答正常（验证选择器迁移未破坏链路）——**LLM 预算 ≤2 次问答**；新建/切换/改名/删除会话正常；「退出」→ 回登录页（再登录可回跳）。
3. 截图 3–4 张（整栏布局 / 数据源下拉展开 / 选中态 / 底部用户区）存 `docs/dispatch/T27-evidence/`。
4. HMR 会让用户当前打开的页面实时变化，属预期行为，无需处理。

## 报告要求（写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T27-report.md`）

- 文件清单；验收证据（截图路径）；与参考图的对应关系说明（哪三点对应哪三段）
- 偏差与自主决策；提交 hash；遗留问题
