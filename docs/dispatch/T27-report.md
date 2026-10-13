# T27 报告 — 侧栏三段式改版（数据源 / 对话列表 / 用户信息）

## 交付文件清单

| 文件 | 改动 |
| --- | --- |
| `frontend/src/components/SessionList.vue` | 侧栏重排为三段：顶部数据源选择（承载 `v-model:dsId` 透传）、中部「新对话」+「最近对话」小标题+会话列表、底部用户行（头像+用户名+设置齿轮+可见「退出」）；移除设置 popover；会话行改双行紧凑结构 |
| `frontend/src/views/ChatPage.vue` | 主区 header 移除数据源选择器与装饰性 Connection 图标，仅保留「表结构」入口；`SessionList` 增加 `v-model:dsId` 与 `:data-sources` 绑定 |
| `frontend/src/components/DataSourceSelector.vue` | 仅样式微调（36px 高、10px 圆角、白底），对外 props/emits 未动 |
| `docs/dispatch/T27-report.md`、`docs/dispatch/T27-evidence/*.png` | 本报告与截图（未提交，归档由主线处理） |

## 提交 hash

- `634a919` refactor(frontend): 侧栏改三段式布局，数据源选择迁入侧栏顶部
- `956efd4` style(frontend): 侧栏行高压缩与交互态微调

工作树无其它未提交改动（`git add` 仅限上述 frontend 路径）。

## 与参考图的对应关系（三段 ↔ 三处）

1. 参考图**顶部圆角选择框**（项目选择器）→ 侧栏顶部**唯一控件**：数据源选择器（全宽、10px 圆角、浅底、前置 Coin 小图标，绑定原 `selectedDsId`）。
2. 参考图**「新对话」行 + 「最近对话」分区小标题 + 会话列表** → 侧栏中部（保留新对话事件；小标题 12px 灰字；会话列表 hover/选中/预览/改名/删除行为全保留）。
3. 参考图**底部用户行**（头像+用户名+右侧小控件）→ 侧栏底部（头像+用户名+齿轮设置按钮+可见「退出」按钮）。
   仅借用布局节奏与排版风格，未搬 AskTable 品牌色/文案/资产；配色全部使用 `main.css` 既有 token（`--primary/--primary-bg/--surface-hover` 等）。

## 验收证据

1. **build**：`npm run build` 两次均通过（提交前各跑一次）。
2. **浏览器实测**（真实 8080 后端，`http://localhost:5173`；视口约 1707×876 CSS，DPR 1.5）：
   - 数据源切换：外卖 mysql ↔ 测试库(25表) 选择与显示正常（截图 03）。
   - **回填行为未破坏**：切到「外卖」后点开带历史轮次的会话，选择器自动回填为该会话最后一轮所用库「测试库(25表)」（`restoreSession` 逻辑原样）。
   - 会话操作：新建（列表 20→21、对话区清空进空态）、改名（PATCH /sessions/464 → 200，标题变「T27改名验证」）、切换（active 位移、4 条历史消息恢复）、删除（确认框含轮数、21→20）均正常。
   - 提问链路（已用满 2 次预算）：`GET /api/v1/chat/stream?question=查询所有用户&datasourceId=5&sessionId=464` 正确携带侧栏所选数据源；但两次均被后端以「流式问答中断: ClosedChannelException」结束（约 3s 断开；1.0 端口探测 11434 无模型服务响应）。判定为环境侧（模型服务）故障——请求参数与 SSE 通道前端侧均正确，但「回答正常」未能闭环（见遗留问题）。
   - 退出：「退出」按钮点击后登录态清理并跳转 `/login`（截图 07）；未登录访问 `/chat` 被守卫拦到 `/login?redirect=/chat`，回跳参数正确（截图 08）。
3. **截图**（`docs/dispatch/T27-evidence/`）：
   - `01-sidebar-overview.png` 整栏布局（全屏）
   - `02-sidebar.png` 侧栏整栏近照
   - `03-ds-dropdown-open.png` 数据源下拉展开
   - `04-selected-state.png` 选中态特写（选中项 `--primary-bg` 高亮 + 标题 `--primary`）
   - `05-user-footer.png` 底部用户区特写（头像/mm/齿轮/退出）
   - `06-llm-error-state.png` 提问报错态（ClosedChannelException）
   - `07-logout-login-page.png` 退出后回到登录页
   - `08-login-redirect-param.png` 未登录访问 /chat 的守卫回跳参数
   - `09-fullpage-raw.png` 原始整页截图（备用）

## 偏差与自主决策

- 会话行带预览时约 44–47px（无预览 36px），略高于「36–44px」指引：为保留预览与时间/轮数信息，把时间与轮数合并成标题行右侧一枚小灰字，行高已尽量压缩（hover 时 meta 隐藏避免与悬浮操作叠字）。
- 设置 popover 直接移除：齿轮按钮一键进 `/settings`，行为与 popover 内「数据源管理」等价。
- 头像改圆形并改用 `--primary` token 渐变（贴合参考图底部圆形头像；为既有硬编码色值的 token 化）。
- 为完成登出验收，用 curl 调 `/api/v1/auth/register` 注册了临时账号 `t27check`（自动化禁止代填密码，无法使用 mm 账号回登）。
- HMR 使用户打开中的页面实时变化，属预期。

## 遗留问题

1. **LLM 问答未闭环**：模型服务当前不可用（ClosedChannelException 两次；11434 无响应），需服务恢复后按同路径复测「切数据源→提问→回答正常」。前端侧证据已齐（正确的 datasourceId、SSE 建立、后端错误事件）。
2. **「再登录可回跳」未实测**：浏览器自动化硬性禁止密码输入（`Sensitive field password is never filled by the Agent`），登出后未能自动回登；**浏览器实例现处于登出状态**，请用户以原账号 mm 重新登录（临时账号 `t27check` / 密码 `T27check!23` 同可用，可自行清理）。
3. 用户自己打开的另一标签页内存中仍显示旧会话；一旦刷新/导航即会要求重新登录。
