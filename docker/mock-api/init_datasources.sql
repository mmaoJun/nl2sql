-- T23：REST / GraphQL 演示数据源入库（幂等，按 name+type 去重）
-- 目标库：nlp2sql_meta；前置：mock-api 服务按 docker/mock-api/README.md 启动（端口 3100）
-- 说明：当前环境为经后端 HTTP 接口创建（database_name 非空），本脚本供全新环境一键重建。

USE nlp2sql_meta;

INSERT INTO datasources (name, type, host, port, database_name, username, password, extra_config)
SELECT 'REST 演示服务', 'rest', 'localhost', 3100, '/api', 'demo', 'demo',
       '{"headers":{"X-API-KEY":"demo-key-2026"},"tables":[{"name":"department_budgets","path":"/departments"}]}'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM datasources WHERE name = 'REST 演示服务' AND type = 'rest');

INSERT INTO datasources (name, type, host, port, database_name, username, password, extra_config)
SELECT 'GraphQL 演示服务', 'graphql', 'localhost', 3100, '/', 'demo', 'demo',
       '{"headers":{"X-API-KEY":"demo-key-2026"},"tables":[{"name":"department_budgets","query":"{ departmentBudgets { deptId deptName budget } }"}]}'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM datasources WHERE name = 'GraphQL 演示服务' AND type = 'graphql');
