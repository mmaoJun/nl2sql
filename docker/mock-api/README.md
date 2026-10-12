# mock-api —— T23 演示用本地 API 服务（REST + GraphQL）

课题目录中「API 类数据源」的演示对象：不依赖外网、数据可控可复现，供
`RestApiAdapter` / `GraphQLAdapter` 拉取，并与 MySQL（`nlp2sql_sample`）做
对话式融合演示。

## 启动 / 停止

```bash
cd F:\FANG_ZHENG\nl2sql\docker\mock-api
npm install          # 首次：安装 express / graphql（node_modules 不入库）
npm start            # 或 node server.js；前端或测试需要该进程常驻
# 停止：Ctrl+C，或 taskkill /FI "WINDOWTITLE eq *server.js*" /IM node.exe（按需）
```

- 默认端口 3100（`PORT` 环境变量可覆盖），默认鉴权头值 `demo-key-2026`（`API_KEY` 可覆盖）。
- 测试与演示前确认 `curl http://localhost:3100/health` 返回 200；`mvn package` 中
  相关用例带 assume 守卫（3100 不通时自动跳过）。

## 端点

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/health` | 无 | 存活探测，返回 `{status: "UP"}` |
| GET | `/api/departments` | `X-API-KEY` | 部门预算数组 `[{deptId, deptName, budget}]` |
| POST | `/graphql` | `X-API-KEY` | GraphQL，查询 `{ departmentBudgets { deptId deptName budget } }` |

两个业务端点缺失/错误的 `X-API-KEY` 一律返回 **401**：

```json
{"error":"unauthorized","message":"缺少或错误的 X-API-KEY 请求头：请在请求头携带 X-API-KEY: demo-key-2026"}
```

## 示例 curl（实测命令）

```bash
# 健康检查（无鉴权）
curl -s http://localhost:3100/health

# 缺少 X-API-KEY -> 401
curl -s -i http://localhost:3100/api/departments

# REST 正常 200
curl -s -H "X-API-KEY: demo-key-2026" http://localhost:3100/api/departments

# GraphQL 正常 200
curl -s -X POST http://localhost:3100/graphql \
  -H "Content-Type: application/json" \
  -H "X-API-KEY: demo-key-2026" \
  -d '{"query":"{ departmentBudgets { deptId deptName budget } }"}'
```

## 数据说明（与 MySQL 对齐）

`deptId / deptName` 与 `nlp2sql_sample.departments` 实际记录对齐（2026-10-12 实测）：

| deptId | deptName | budget（演示值，万元） |
|---|---|---|
| 1 | 技术部 | 880 |
| 2 | 市场部 | 460 |
| 3 | 财务部 | 520 |
| 4 | 人事部 | 240 |
| 5 | 运营部 | 310 |

跨源融合演示中：预算来自本服务（API 侧），员工数来自 MySQL `users` 表
（按 `department_id` 关联 `departments`）。

## 数据源入库（nlp2sql_meta.datasources）

`init_datasources.sql` 为幂等的入库脚本（按 name+type 去重），可直接在 MySQL 里执行；
记录与适配器 `extra_config` 的对应关系见下。当前环境两条记录由 T23 通过
后端 HTTP 接口创建（接口对 database_name 有非空校验，取 `/api` / `/`）。

```sql
-- 也可用 mysql 客户端直接执行脚本：
mysql -u root -p < init_datasources.sql
```

`extra_config`（表模型契约，详见 T23 报告）：

```json
// REST 演示服务（type=rest）：
{"headers":{"X-API-KEY":"demo-key-2026"},
 "tables":[{"name":"department_budgets","path":"/departments"}]}
// database_name=/api 作 basePath，实际请求 http://localhost:3100/api/departments

// GraphQL 演示服务（type=graphql）：
{"headers":{"X-API-KEY":"demo-key-2026"},
 "tables":[{"name":"department_budgets","query":"{ departmentBudgets { deptId deptName budget } }"}]}
// database_name=/ 即 basePath 为空，实际请求 http://localhost:3100/graphql
```
