# T23 派发说明 — API 适配器（REST + GraphQL）与对话式融合

你是实现子代理，只依据本文件执行，不读其他计划文档。完成后写报告到 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T23-report.md`。

## 背景与已定方案

T21/T22 已完成（Hive、HDFS 文件适配器）。本任务补齐课题目录最后一类数据源：**API（REST + GraphQL）**，并给"API 调用结果与数据库结果融合"提供可演示形态。

**已与用户定案**：
- 演示 API 用**本地 mock 服务**（可控可复现，不依赖外网）
- 融合形态 = **对话式融合**：同一会话先后对 MySQL 与 API 两个数据源提问，Agent 借助会话历史把两侧真实数值汇总对比（演示时如实说明这是应用层融合的取舍）

**前序可参考**（只读上下文）：T22 的 `HdfsFileAdapter`（非 JDBC 适配器的实现模式、JSqlParser 提表名、H2 内存执行、类型推断与 CAST 思路、`assume` 守卫测试风格）。**先读 T22 报告与代码再动手。**

## 交付物

1. **本地 mock 服务** `docker/mock-api/`（Node，端口 3100）：
   - `GET /health` → 200
   - REST：`GET /api/departments` → 部门预算数组（如 `[{deptId, deptName, budget}]`）；**数据与 `nlp2sql_sample.departments` 的部门 id/名称对齐**（先查 MySQL 实际部门清单再写数据）
   - GraphQL：`POST /graphql`，支持查询 `{ departmentBudgets { deptId deptName budget } }`
   - **鉴权**：两个接口要求请求头 `X-API-KEY: demo-key-2026`，缺失返回 401（课题要求"API 调用鉴权"）
   - `package.json` + `server.js`（依赖清单最小化，如 express/graphql 及版本），`node_modules` 不入库；README（启动命令、端点、示例 curl）
2. **适配器** `adapter/api/`：
   - `RestApiAdapter` + Factory（code `rest`、displayName `REST API`、defaultPort 3100）
   - `GraphQLAdapter` + Factory（code `graphql`、displayName `GraphQL API`、defaultPort 3100）
   - 均直接实现 `DataSourceAdapter`（非 JDBC）；**表模型**：`extra_config` JSON 支持 `tables: [{name, path(REST)|query(GraphQL)}]`+ `headers`（鉴权头）——单表即数组一个元素；`database_name` 可空或作默认 basePath
   - 方法：`testConnection`（/health）、`getTables`（config 声明）、`getColumns`（采样响应推断列，类型推断思路同 T22）、`executeQuery`（JSqlParser 提表名 → 拉 JSON → H2 内存表（数值列保证 SUM/COUNT 可用，参考 T22 的 CAST 经验）→ 执行 SQL → QueryResult；`setMaxRows` 兜底）
   - 响应→行集：顶层对象数组为标准形态；单对象视为单行；嵌套对象/数组值序列化为字符串（报告说明）
3. **数据源入库**（`nlp2sql_meta.datasources`，幂等）：
   - `REST 演示服务`（type=rest, host=localhost, port=3100, extra_config 含 headers+tables）
   - `GraphQL 演示服务`（type=graphql, ...同上）
   - 报告两个 id
4. **测试**（`assume` 守卫 3100 可达；服务未起时跳过并在报告中提示启动命令）：
   - 适配器集成：REST/GraphQL 各自 表清单/列/聚合查询（按 deptName 分组可得）；**鉴权用例**：缺 X-API-KEY 时请求被拒（401）且消息清晰
   - **对话式融合 E2E（W4 演示的预演，真实本地 7B）**：同一会话两问——①对 MySQL 数据源（`nlp2sql_sample`）问"各部门分别有多少名员工？"；②切 REST API 数据源问"把各部门的预算和刚才的员工数放在一起对比一下"→ 断言第二轮回答**同时引用**两边的真实数值（部门名+员工数+预算）。**注意 T11 教训：不要要求 7B 做心算断言**（如"算人均"），融合断言只要求并列引用两侧真实数值
5. 不改 T2-T22 既有文件（新增为主）。
6. mock 服务需**在测试与演示时运行**：报告写清启动/停止命令；`docker/mock-api/README.md` 给运维说明。

## 硬约束

- **真实 LLM 预算：≤3 次问答**（融合两问 + 最多 1 次复验）；模型行为问题最多修 1 次，不无限调参。
- 不实现前端；不改 Hive/HDFS compose。
- git：只 `git add backend/ docker/` 本任务路径；**禁止 `git add -A` / `git add .`**；中文 conventional commit，3 个左右提交（mock 服务 / 适配器 / 测试）。
- 工作树中非本任务文件不要动。

## 验证（实际执行，真实输出进报告）

1. `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package`：59 例 + 新增全绿（贴 surefire 行）。
2. 报告贴：mock 服务 curl 实测（含 401 与 200）；两适配器的真实调用输出；融合 E2E 两轮轨迹摘要与第二轮回答全文。

## 报告要求（写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T23-report.md`）

- 文件清单；表模型与 extra_config 契约（写清楚，W4 收尾与演示文档要用）
- 数据源 id；验证命令真实输出关键行
- 融合形态说明（对话式、经会话历史、应用层合成）与演示脚本建议
- 偏差与自主决策；提交 hash；遗留问题
