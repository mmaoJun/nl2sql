# T23 实施报告 — API 适配器（REST + GraphQL）与对话式融合

- 状态：**完成**。原派发子代理在融合 E2E 的 7B 行为调试中触达回合上限被中断（代码已全部落盘、零提交）；主线接管后通过受控探针定位根因、完成修复链并全量回归 —— **65 例 0 失败**。
- 提交：`feat(mock-api): 本地 REST+GraphQL 演示服务…` → `feat(adapter): REST/GraphQL API 适配器…` → `feat(agent): 跨源会话历史与 7B 行为修复…` → `test(agent): API 适配器与跨源融合用例`（docs 归档随 W4 批次）
- 环境：MySQL ✓、Ollama ✓、Hive/HDFS 容器 ✓、mock-api(3100) ✓

## 1. 交付物

| 类别 | 内容 |
|---|---|
| mock 服务 | `docker/mock-api/`：`server.js` / `package.json` / `README.md` / `init_datasources.sql`（幂等入库）；REST `/api/departments`、GraphQL `POST /graphql`、`/health`；**X-API-KEY 鉴权**（缺失 401）；预算数据与 `nlp2sql_sample.departments` 对齐（技术部 880 / 市场部 460 / 财务部 520 / 人事部 240 / 运营部 310） |
| 适配器 | `adapter/api/`（7 文件）：`ApiConfig`（extra_config 解析：`headers` + `tables[{name, path\|query}]`）、`ApiDataClient`、`JsonTableExecutor`（JSqlParser 提表名 → 拉 JSON → H2 内存执行，类型推断与 T22 同口径）、`RestApiAdapter(+Factory)`、`GraphQLAdapter(+Factory)`；**引导式报错**（见 §4） |
| 数据源入库 | `REST 演示服务` id=14、`GraphQL 演示服务` id=15 |
| 跨源会话 | `ChatService`/`ChatSessionService`：历史升级为**跨数据源**注入（新增 `recentSessionTurns`），支撑对话式融合 |
| 提示词 | 本地/云端规则 5 收紧为"**最新一条**用户消息中给出数据源ID"；规则 6 跨源说明；**本地新增「多轮对比示例」（few-shot，决定性修复）**；`execute_sql` 工具描述补"不支持跨数据源 JOIN" |
| 测试 | `ApiAdapterIntegrationTest` 5 例（含鉴权 401、双适配器聚合查询）；`CrossSourceFusionE2ETest` 1 例（真实 HTTP + 会话历史 + 双数据源） |

## 2. mock 服务契约

| 方法 | 路径 | 鉴权 | 返回 |
|---|---|---|---|
| GET | /health | 无 | 200 |
| GET | /api/departments | X-API-KEY: demo-key-2026 | `[{deptId, deptName, budget}]`（5 部门） |
| POST | /graphql | 同上 | `{ departmentBudgets { deptId deptName budget } }` |

启动：`cd docker/mock-api && npm install && npm start`（测与演示期间需常驻）。

## 3. 探针实验（主线接管的核心诊断工作）

融合 E2E 反复失败但每次现象不同，测试内断言无法区分原因，于是用**运行中的后端 + curl 文件体**做单变量探针（每探针 1 次真实 LLM）：

| 探针 | 条件 | 结果 |
|---|---|---|
| P1 | 无历史 · 对比句 · REST 源 | 查了表但未执行查询，回复"请提供进一步指令" |
| P2 | 无历史 · 简单问 · REST 源 | ✅ 完整查询作答（REST 链路本身无误） |
| P3a | 首轮 · MySQL 源 | ✅ 正常 |
| **P3b** | **有历史 · 简单追问（换源）** | ❌ **拒答**（0 工具，"无法执行写操作"） |

**结论：触发器不在问句，在"历史 + 当前两组不同的『数据源ID为 N』"**——本地 7B 把该句式对偶理解为"把数据源改成 14"的状态修改指令，撞上写操作禁止规则而拒答。同源多轮（ID 相同）不触发，与 T11 历次通过完全吻合。

## 4. 修复链（按根因分层，全部保留为产品级防御）

| # | 问题 | 修复 | 性质 |
|---|---|---|---|
| 1 | 拒答（根因） | 历史轮次**不再携带「数据源ID为 N」包装**，全上下文仅保留当前消息一处声明 | 根因修复 |
| 2 | 模型仍把历史表名当本源表、尝试跨源 JOIN 耗尽迭代 | 规则 6 + `execute_sql` 工具描述 + **API 适配器引导式报错**（未知表 → 抛出"可用表清单 + 不支持跨源 JOIN + 历史数字直接引用"） | 三层防御 |
| 3 | 显式"不用重查"又令模型向用户索要数据 | **few-shot「多轮对比示例」**（小模型对示例学习远强于规则）——最终稳定通过 | 决定性修复 |
| 4 | 客户端 10s 读超时误杀长请求（round-1 9.6s 险过） | 测试客户端 readTimeout → 3 分钟 | 测试基建 |
| 5 | Q1 拿不到部门名（子代理期内完成） | 问题附库内关系提示并要求按名称列出 | 行为适配 |

## 5. 融合形态（如实说明）

**对话式融合**：同一会话两轮——第 1 轮对 MySQL 问员工数、第 2 轮切 REST API 问预算，**第 2 轮回答借助会话历史并列引用两源真实数值**。系统**不支持**跨数据源 SQL JOIN（提示词与适配器报错均明确约束）；演示两问措辞见测试常量（Q2："各部门的预算分别是多少？回答时请把每个部门对应上一轮的员工数一起列出来。"）。

## 6. 验证（真实输出）

全量：`mvn -q clean package` → **Tests run: 65, Failures: 0, Errors: 0, Skipped: 1**（云端 smoke 缺 key 按设计跳过），BUILD SUCCESS。

融合 E2E 最终轨迹：

```
第1轮(MySQL)  工具: list_tables → get_schema → get_schema → execute_sql（departments JOIN users 分组）
第1轮(MySQL)  回答: 技术部有 5 名员工，市场部和财务部各有 3 名员工，人事部和运营部各有 2 名员工。
第2轮(REST API) 工具: list_tables → get_schema → execute_sql（SELECT deptId, deptName, budget FROM department_budgets）
第2轮(REST API) 回答: 技术部有 5 名员工，预算为 880；市场部有 3 名员工，预算为 460；财务部有 3 名员工，
              预算为 520；人事部有 2 名员工，预算为 240；运营部有 2 名员工，预算为 310。
```

## 7. 7B 行为边界记录（供《7B 模型能力评估报告》）

1. 规则性约束（"不要做 X"）对 7B 的约束力有限；**few-shot 示例是最有效杠杆**（工具调用格式、多轮对比均靠示例学会）。
2. 三种失败模式及触发条件：拒答吸引子（双 ID 声明句式）、跨源 JOIN 执念（历史表名混淆）、向用户索要上下文已有数据（过度显式指令）。
3. 建议评估报告加入对照：同类题型在云端 qwen-plus 上的表现（预计无此问题，可作 7B/大模型边界实例）。

## 8. 遗留

1. GraphQL 适配器的 LLM 端到端用例未加（适配器集成已覆盖；演示走同一执行链）。
2. HDFS 适配器的未知表报错未做同款引导文案（同类需求，可后续统一）。
3. 演示环境需同时起：MySQL、Ollama、Hive/HDFS 容器、mock-api(3100)；T12/T23 联调在库里留有测试会话/用户（可择机清理）。
