# T6 派发说明 — frontend 骨架（Vue3 + Element Plus 壳）

你是实现子代理，只依据本文件执行，不读其他计划文档。完成后写报告到 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T6-report.md`。

## 并行执行须知（重要）

- 本任务与另一个子代理（后端 T5）**同时在同一个仓库工作**：你只允许在 `F:\FANG_ZHENG\nl2sql\frontend\` 下创建/修改文件；`backend/` 的一切变化与你无关，不要碰。
- **禁止执行任何 git 命令**（不 add、不 commit、不 stash、不 checkout）：提交由主线在 T5 完成后统一处理。工作树里看到的其他人的未提交改动不要动。

## 背景

新工程 `F:\FANG_ZHENG\nl2sql` 已建 backend/（Spring Boot + AgentScope，T1–T5/T7）。本任务搭 frontend 骨架：从旧项目 `F:\FANG_ZHENG\nlp2sql\frontend`（**只读**）移植工程配置与页面壳，保证 `npm run build` 与 `npm run dev` 可用、页面可渲染。与后端的功能联调属后续周（W3/W4），本任务不要求登录等流程真实可用。

## 交付物

1. `F:\FANG_ZHENG\nl2sql\frontend\` 工程：
   - `package.json`：名称改 `nl2sql-frontend`；依赖与版本**照抄**旧 package.json（Vue 3 / Vite 5 / Element Plus / Pinia / vue-router / axios / 图标库；devDependencies 同样照抄），scripts 保持 dev / build / preview
   - `vite.config.js`：端口 5173；`/api` 代理到 `http://localhost:8080`（changeOrigin）
   - `index.html`、`src/main.js`、`src/App.vue`、`src/assets/`、`src/router/`、`src/stores/`、`src/api/`（或旧工程等价目录）、`src/views/*`（LoginPage / ChatPage / SettingsPage）、`src/components/*`（ChatInput / ChatMessage / DataSourceSelector / ModelSelector / ResultTable / SchemaExplorer / SessionList 等）——整体从旧工程移植
2. 适配点（最小改动，逐一报告）：
   - 包名、页面标题等品牌字样 `nlp2sql` → `nl2sql`（全库搜索一遍，报告改了哪些）
   - 明确无引用的残留组件可删（如 HelloWorld 之类；删前确认路由/引用，报告说明）
   - 不新增页面、不改交互逻辑；API 层端点保持不变（多数与新后端对齐，联调在后续周）
3. 验证（必须实际执行，真实输出进报告）：
   - `cd frontend && npm install`（本机 registry 若慢，报告实际耗时；失败则贴完整报错）
   - `npm run build` 通过（贴关键输出行）
   - `npm run dev` 后台启动后 `curl -s http://localhost:5173/` 返回 200 且含 index.html 特征（验证完停掉进程，确认端口释放）
4. **报告写 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T6-report.md`**：文件清单（按目录汇总）、验证命令真实输出、品牌字样适配清单、删除清单、偏差、遗留（供 W3/W4 前端联调参考）。

## 硬约束

- 只在 `frontend/` 下写文件；不改 `backend/`、`docs/` 既有文件（报告文件除外）、根 README。
- 不执行任何 git 相关命令；不建空文件、空目录。
