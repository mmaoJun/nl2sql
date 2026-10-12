# T2 派发说明 — SQL 安全校验迁移（SqlSecurityChecker + 测试补强）

你是实现子代理，只依据本文件执行，不读其他计划文档。完成后写报告到 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T2-report.md`。

## 背景

新工程 `F:\FANG_ZHENG\nl2sql\backend` 骨架已就绪（T1：commits `0358128` / `5ccb325`，包根 `com.nl2sql`，Spring Boot 3.3.4 + JSqlParser 4.9 已在 pom）。本任务把旧项目的 SQL 安全校验迁移过来并补齐测试——它是 T4 `validate_sql` 工具的直接依赖。

来源（**只读参考，不改旧仓库**）：
- `F:\FANG_ZHENG\nlp2sql\backend\src\main\java\com\nlp2sql\security\SqlSecurityChecker.java`（171 行）
- `F:\FANG_ZHENG\nlp2sql\backend\src\test\java\com\nlp2sql\security\SqlSecurityCheckerTest.java`（70 行）

## 交付物

1. `com.nl2sql.security.SqlSecurityChecker`：与旧实现**公开 API 和行为完全一致**（`validate(String)` → 嵌套 `record ValidationResult(boolean valid, String errorMessage)`、`enforceLimit(String, int)`），仅做包名迁移；依赖保持 JSqlParser + Lombok + Spring `@Component`。
2. `com.nl2sql.security.SqlSecurityCheckerTest`：迁移旧测试，并对照课题安全要求逐项核查覆盖，缺失的补上：
   - ① 关键词黑名单（DROP / DELETE / INSERT / UPDATE / TRUNCATE 等）
   - ② JSqlParser AST 校验（仅 SELECT 放行）
   - ③ 堆叠查询（`SELECT 1; DROP TABLE x`）
   - ④ UNION 注入
   - ⑤ 注释绕过（`--` / `/* */` 包裹的违禁语句）
   - ⑥ 字面量 / 引号标识符误伤（旧测试已有，保留）
   - ⑦ `enforceLimit` 的行为（仅在确实没有 LIMIT 时追加）
3. 若核查中发现旧实现存在课题要求未覆盖的校验缺口（如某种注入形态漏判），做小步补实现 + 对应测试；**不要重构、不要扩大范围**，每处改动在报告中说明理由。

## 硬约束

- 不改动 T1 已建的契约类；不引入新依赖；不建空包、空文件。
- git：只 `git add backend/` 下本任务相关路径；**禁止 `git add -A` / `git add .`**；中文 conventional commit（建议按"实现迁移 / 测试补强"拆 1–2 个提交）。
- 工作树中非本任务文件（`docs/`、`实习生课题(1).txt` 及其他未提交草稿）不要动。

## 验证（实际执行，真实输出进报告）

1. `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q test -Dtest=SqlSecurityCheckerTest` 全绿（贴测试 summary 行）。
2. 再跑一次 `mvn -q clean package` 确认整体构建仍通过。

## 报告要求（写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T2-report.md`）

- 文件清单；测试方法数与被测项
- 覆盖对照表：课题安全要求 → 对应用例名 → 结果
- 补强项说明（补了哪些用例/实现，为什么）
- 验证命令真实输出关键行；提交 hash；遗留问题
