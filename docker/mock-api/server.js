/**
 * T23 演示用本地 mock API 服务（REST + GraphQL），默认端口 3100。
 *
 * 用途：作为「API 类数据源」的稳定演示对象，供 nl2sql 的 RestApiAdapter /
 * GraphQLAdapter 拉取数据；不依赖外网、数据可控可复现。
 *
 * 端点与鉴权：
 *   GET  /health              —— 存活探测（无鉴权，供适配器 testConnection 用）
 *   GET  /api/departments     —— REST：部门预算数组 [{deptId, deptName, budget}]
 *   POST /graphql             —— GraphQL：{ departmentBudgets { deptId deptName budget } }
 *   两个业务端点都要求请求头 X-API-KEY: demo-key-2026，缺失或错误返回 401。
 *
 * 数据说明：deptId / deptName 与 nlp2sql_sample.departments 实际记录一一对齐
 * （2026-10-12 实测：1 技术部 / 2 市场部 / 3 财务部 / 4 人事部 / 5 运营部），
 * budget 为演示数值（单位：万元），与数据库侧的员工数做跨源对比演示。
 */

const express = require('express');
const { graphql, buildSchema } = require('graphql');

const PORT = Number(process.env.PORT || 3100);
const API_KEY = process.env.API_KEY || 'demo-key-2026';
const KEY_HEADER = 'X-API-KEY';

/** 部门预算演示数据（deptId/deptName 与 nlp2sql_sample.departments 对齐） */
const departmentBudgets = [
  { deptId: 1, deptName: '技术部', budget: 880 },
  { deptId: 2, deptName: '市场部', budget: 460 },
  { deptId: 3, deptName: '财务部', budget: 520 },
  { deptId: 4, deptName: '人事部', budget: 240 },
  { deptId: 5, deptName: '运营部', budget: 310 },
];

const schema = buildSchema(`
  type DepartmentBudget {
    deptId: Int!
    deptName: String!
    budget: Int!
  }
  type Query {
    departmentBudgets: [DepartmentBudget!]!
  }
`);

const rootValue = {
  departmentBudgets: () => departmentBudgets,
};

const app = express();
app.use(express.json());

// 鉴权中间件：两个业务端点统一校验 X-API-KEY（课题要求「API 调用鉴权」）
function requireApiKey(req, res, next) {
  const key = req.get(KEY_HEADER);
  if (key !== API_KEY) {
    return res.status(401).json({
      error: 'unauthorized',
      message: `缺少或错误的 ${KEY_HEADER} 请求头：请在请求头携带 ${KEY_HEADER}: ${API_KEY}`,
    });
  }
  next();
}

// 存活探测（无鉴权）：适配器 testConnection 走这里
app.get('/health', (req, res) => {
  res.json({ status: 'UP', service: 'nl2sql-mock-api', time: new Date().toISOString() });
});

// REST：部门预算数组
app.get('/api/departments', requireApiKey, (req, res) => {
  res.json(departmentBudgets);
});

// GraphQL：POST /graphql，支持 { departmentBudgets { deptId deptName budget } }
app.post('/graphql', requireApiKey, async (req, res) => {
  const { query, variables } = req.body || {};
  if (typeof query !== 'string' || query.trim() === '') {
    return res.status(400).json({ errors: [{ message: '请求体缺少 query 字段' }] });
  }
  const result = await graphql({ schema, source: query, rootValue, variableValues: variables });
  res.status(result.data ? 200 : 400).json(result);
});

app.use((req, res) => {
  res.status(404).json({ error: 'not_found', message: `未知路径: ${req.method} ${req.path}` });
});

app.listen(PORT, () => {
  console.log(`[mock-api] listening on http://localhost:${PORT} (X-API-KEY: ${API_KEY})`);
});
