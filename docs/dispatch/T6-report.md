# T6 报告 — frontend 骨架（Vue3 + Element Plus 壳）

- 交付物：`F:\FANG_ZHENG\nl2sql\frontend\`（25 个文件，不含 node_modules/dist）
- 来源：`F:\FANG_ZHENG\nlp2sql\frontend\`（只读参考，未做任何写入）
- 约束遵守：未执行任何 git 命令；写入范围仅 `frontend/**` 与本报告；`backend/`、其他 `docs/` 文件、根 README 均未触碰。
- 状态：`npm install` / `npm run build` / `npm run dev` + curl 200 全部实测通过（真实输出见 §2）。

## 1. 文件清单（按目录汇总）

| 目录/文件 | 内容 |
|---|---|
| `frontend/` 根 | `package.json`（name=nl2sql-frontend）、`package-lock.json`（照抄旧工程 lockfileVersion 3，仅改名）、`vite.config.js`（端口 5173，`/api`→`http://localhost:8080` changeOrigin）、`index.html`、`.gitignore` |
| `frontend/.vscode/` | `extensions.json`（Volar 推荐） |
| `frontend/public/` | `favicon.svg`（index.html 引用） |
| `frontend/src/` | `main.js`（Pinia + Router + Element Plus zh-cn + 全量图标注册）、`App.vue`（`<router-view/>`） |
| `frontend/src/assets/` | `main.css`（CSS 变量主题 + 全局消息气泡样式） |
| `frontend/src/router/` | `index.js`（/ → /chat、/chat、/settings、/login；守卫要求 localStorage.token） |
| `frontend/src/api/` | `client.js`（axios 实例 baseURL=/api/v1，JWT 注入，401 跳登录）、`endpoints.js`（14 个端点） |
| `frontend/src/stores/` | `chatStore.js`、`datasourceStore.js`、`sessionStore.js` |
| `frontend/src/views/` | `LoginPage.vue`、`ChatPage.vue`、`SettingsPage.vue` |
| `frontend/src/components/` | `ChatInput.vue`、`ChatMessage.vue`、`DataSourceSelector.vue`、`ModelSelector.vue`、`ResultTable.vue`、`SchemaExplorer.vue`、`SessionList.vue` |

## 2. 验证（实际执行的真实输出）

环境：`node v20.15.0`、`npm 10.7.0`、registry `https://registry.npmjs.org`。

### 2.1 `npm install`

命令：`cd frontend && npm install`（耗时 23s：10:51:53 → 10:52:17，EXIT_CODE=0）

```
npm warn EBADENGINE Unsupported engine {
npm warn EBADENGINE   package: '@vueuse/core@15.0.0',
npm warn EBADENGINE   required: { node: '>=22' },
npm warn EBADENGINE   current: { node: 'v20.15.0', npm: '10.7.0' }
npm warn EBADENGINE }
npm warn EBADENGINE   package: '@vueuse/metadata@15.0.0' / '@vueuse/shared@15.0.0' 同上

added 89 packages, and audited 90 packages in 23s

17 packages are looking for funding
3 vulnerabilities (1 moderate, 2 high)
```

不慢（23s，registry 直连可用）。`npm ls --depth=0` 实测安装版本：`vue@3.5.43`、`vite@5.4.21`、`element-plus@2.14.6`、`@element-plus/icons-vue@2.3.2`、`axios@1.20.0`、`pinia@4.0.3`、`vue-router@4.6.4`、`@vitejs/plugin-vue@5.2.4`——全部满足照抄的 `^` 版本范围。EBADENGINE 来自 element-plus 的传递依赖 @vueuse/*，仅警告，不影响构建与运行。

### 2.2 `npm run build`（EXIT_CODE=0）

```
> nl2sql-frontend@0.0.0 build
> vite build

vite v5.4.21 building for production...
✓ 1693 modules transformed.
rendering chunks...
dist/index.html                              0.46 kB │ gzip:   0.33 kB
dist/assets/LoginPage-BbW4lfzI.css           1.98 kB │ gzip:   0.75 kB
dist/assets/SettingsPage-4YPTZUhH.css        5.09 kB │ gzip:   1.29 kB
dist/assets/ChatPage-B2w13zxM.css           12.26 kB │ gzip:   2.58 kB
dist/assets/index-D7OQs8RD.css             362.99 kB │ gzip:  48.89 kB
dist/assets/datasourceStore-CAF6mSA4.js      0.45 kB │ gzip:   0.34 kB
dist/assets/LoginPage-B6sJLUAJ.js            3.54 kB │ gzip:   1.41 kB
dist/assets/SettingsPage-D0jYD29c.js         6.78 kB │ gzip:   2.82 kB
dist/assets/ChatPage-L3NiL7ml.js            17.02 kB │ gzip:   6.80 kB
dist/assets/endpoints-DbbOd2vY.js           52.75 kB │ gzip:  20.09 kB
dist/assets/index-DjVJigLX.js            1,208.36 kB │ gzip: 388.54 kB

(!) Some chunks are larger than 500 kB after minification. ...
✓ built in 11.69s
```

三个页面均被独立分包（LoginPage/SettingsPage/ChatPage），与路由懒加载一致。chunk >500kB 警告为 element-plus 全量引入的既有现状，未改。

### 2.3 `npm run dev` + curl 冒烟

```
> nl2sql-frontend@0.0.0 dev
> vite

VITE v5.4.21  ready in 727 ms

➜  Local:   http://localhost:5173/
```

```
$ curl -s -o /tmp/t6_resp.html -w '%{http_code}' http://localhost:5173/
HTTP_STATUS=200

$ head -15 /tmp/t6_resp.html
<!doctype html>
<html lang="en">
  <head>
    <script type="module" src="/@vite/client"></script>
    <meta charset="UTF-8" />
    <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>NL2SQL 智能查询系统</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.js"></script>
  </body>
</html>

$ curl -s -o /dev/null -w "%{http_code}" http://localhost:5173/src/main.js
200
```

停进程与端口释放：先 `taskkill //PID $!` 未命中（`$!` 是 Git Bash 的 job pid，非 Windows pid），改用 `netstat -ano` 取监听 PID 23876 → `taskkill //PID 23876 //T //F` 成功杀掉 node 及其子进程；复查 `netstat -ano | grep :5173`：

```
  TCP    [::1]:57026            [::1]:5173             TIME_WAIT       0
  TCP    [::1]:57028            [::1]:5173             TIME_WAIT       0
```

已无 LISTENING 项，仅剩客户端侧 TIME_WAIT（内核自动回收）→ 5173 已释放。

## 3. 品牌字样适配清单（全库 grep 后逐项报告）

搜索：`grep -i "nlp2sql|frontend|NL2SQL"`，范围 `frontend/**`（排除 node_modules、dist）。

- 前置事实：**旧工程源码里原本就没有 `nlp2sql` 字样**（对 `nlp2sql/frontend` 全目录 grep 无命中），`nlp2sql`→`nl2sql` 无直接替换项；品牌字样实际是脚手架残留的 `frontend`。改动 3 处：
  1. `package.json`：`"name": "frontend"` → `"nl2sql-frontend"`
  2. `package-lock.json`：根 `name` 与 `packages[""].name` 两处 → `"nl2sql-frontend"`（依赖版本信息一字未动）
  3. `index.html`：`<title>frontend</title>` → `<title>NL2SQL 智能查询系统</title>`
- 无需改动：`LoginPage.vue` 登录卡片标题旧工程即为 `<h2>NL2SQL</h2>`，已是目标品牌。
- 复核结果：`grep -i nlp2sql` 全库 0 命中；`grep '"name": "frontend"'` 0 命中；`<title>frontend</title>` 0 命中；`NL2SQL` 命中 2 处（index.html title、LoginPage h2）。

## 4. 删除清单（未移植项及理由）

| 未移植项 | 理由 |
|---|---|
| `src/components/HelloWorld.vue` | Vite 脚手架残留组件，旧工程无任何路由/组件引用（grep 全库仅它自己引用下图资源） |
| `src/assets/hero.png`、`vite.svg`、`vue.svg` | 仅被 HelloWorld.vue 引用，随其删除成为死资源（约 23KB） |
| `public/icons.svg` | 仅被 HelloWorld.vue 的 `<use href="/icons.svg#...">` 引用（documentation/social/github/discord/x/bluesky 图标） |
| 旧工程 `dist/` | 构建产物不移植；新工程已自行 build 生成 `dist/`（已列入 frontend/.gitignore） |
| 旧工程 `README.md` | Vite 模板通用文案，非交付清单项，未移植 |

删除前确认：上述组件/资源在新工程 grep 均无引用（`HelloWorld|hero.png|vite.svg|vue.svg|icons.svg` 在 frontend 下 0 命中）。

保留说明：
- `public/favicon.svg`：index.html 引用，保留。
- `src/components/SchemaExplorer.vue`：交付清单明确列入移植范围，旧工程也未挂到任何页面（当前无引用），保留供 W3/W4 表结构浏览接入；其内部依赖的 `getTables`/`refreshMetadata` 端点已随 `endpoints.js` 就位。
- `.gitignore`、`.vscode/extensions.json`：工程配置，随工程移植。

## 5. 偏差与决策说明

1. 仅按交付清单移植，未新增页面、未改交互逻辑、未改路由规则，API 端点（含 baseURL `/api/v1`）保持旧工程原样。
2. `SchemaExplorer.vue` 保持"已移植但无引用"状态（与旧工程一致），未强行接入页面（属联调范围）。
3. 删除范围略大于"组件"字面（连带 HelloWorld 独占的 4 个脚手架资源），理由与清单见 §4，确保无悬空引用。
4. `index.html` 的 `<html lang="en">` 未改（旧工程如此，页面实际为中文），遵循最小改动。
5. 未移植旧工程 `README.md` 与 `dist/`；新 `dist/` 由本次 build 生成，已被 .gitignore 覆盖。

## 6. 遗留问题（供 W3/W4 前端联调参考）

1. **端点对齐待核对**：`src/api/endpoints.js` 保留旧工程 14 个端点，需与 T5 新后端 controller 逐一比对。重点契约：`POST /api/v1/chat` 请求体 `{ message, datasourceId, model, sessionId }`，响应 `{ success, rowCount, executionTimeMs, generatedSql, columns, rows, modelName, modelProvider, resolvedQuestion, rewriteStatus, errorMessage }`；`/sessions` 列表项 `{ id, title, preview, updatedAt, turnCount }`；`/sessions/{id}/turns` 项 `{ question, resolvedQuestion, rewriteStatus, status, rowCount, executionTimeMs, generatedSql, errorMessage, modelName, modelProvider, createdAt, datasourceId }`；业务错误码约定 `3001 = 会话不存在`（ChatPage 依赖它决定清理本地 sessionId）。字段不齐会直接导致历史会话回放/改写提示条异常。
2. **登录链路未联调**：路由守卫要求 `localStorage.token`；`LoginPage` 真实调用 `/auth/login`、`/auth/register`。若新后端暂无 `/auth/*`，W3 需补接口或调整守卫策略，否则页面会停在登录页（本次冒烟仅验证静态壳，未过登录）。
3. **首屏体积**：`index-*.js` 1.2MB（Element Plus 全量 + main.js 全量图标注册）。若 W4 关注首屏，可改按需自动导入 / 图标按需注册。
4. **@vueuse/* 要求 node>=22** 的 EBADENGINE 警告：node 20.15.0 下构建运行正常，但 CI/服务器建议统一 node 22。
5. **部署路径未定**：dev 代理仅 `/api`→8080；生产需 Nginx 反代或后端托管 `dist/` 的约定（含 SPA fallback 到 index.html）。
6. **SchemaExplorer 接入位置未定**（ChatPage 侧栏或 SettingsPage 抽屉），需与产品形态一起定。
7. 前端无 ESLint / 单测配置（旧工程亦无），`npm run build` 是当前唯一静态关卡；如需门槛建议 W4 补。
