# T4 派发说明 — 5 个 @Tool 工具与独立调用验证（W2 检查点核心）

你是实现子代理，只依据本文件执行，不读其他计划文档。完成后写报告到 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T4-report.md`。

## 背景

新工程已具备全部依赖件：SQL 安全校验（T2：`10beac3`/`b97e2df`，`com.nl2sql.security.SqlSecurityChecker`）、数据源适配与持久化（T3：`04c462f`/`502c1f3`/`ecb70db`）、元数据与压缩（T7：`98da71c`/`4c1bc12`/`e07693e`，`com.nl2sql.service.MetadataService`）。本任务实现 **W2 检查点核心：5 个工具可独立调用**。

## AgentScope 2.0.2 真实 API（已 javap 核实；写码前仍以本地 jar 为准）

- `io.agentscope.core.tool.Tool` 是注解：`@Tool(name, description, strict, readOnly, concurrencySafe, externalTool, stateInjected, dangerousFiles, dangerousDirectories, converter)`（后四项一般用默认）
- 参数注解 `io.agentscope.core.tool.ToolParam`：`@ToolParam(name, required, description)`
- 注册：`new Toolkit().registerTool(Object)`（反射扫描该对象的 @Tool 方法）；`getToolNames()` / `getToolSchemas()` 可自检
- schema 反射依赖方法参数名保留 → spring-boot-starter-parent 已默认 `-parameters`，**不要改编译器配置**
- jar：`E:\qqdownload\javaweb\warehouse\io\agentscope\agentscope-core\2.0.2\agentscope-core-2.0.2.jar`（不确定的类/方法用 `javap -cp` 自查）

## 交付物

1. `com.nl2sql.tool.NL2SQLTools`（`@Component`；构造注入 `MetadataService`、`DataSourceService`、`DataSourceFactory`、`SqlSecurityChecker`；行数上限用 `@Value("${nl2sql.sql-security.max-limit:100}")`），5 个方法（签名对齐《技术方案书》§5.2；全部 `readOnly = true`）：
   - `list_tables(datasource_id)` → `List<TableInfo>`：走 `MetadataService.getTables`
   - `get_schema(datasource_id, table_name)` → `List<ColumnInfo>`：走 `MetadataService.getColumns`
   - `sample_data(datasource_id, table_name, limit=3)` → `QueryResult`：适配器 `executeQuery` 取前 N 行（N 缺省 3、不超过 max-limit）。**表名拼接是注入面：构造的 SQL 必须先过 `SqlSecurityChecker.validate`**
   - `validate_sql(datasource_id, sql)` → 校验结果（`SqlSecurityChecker.validate` 的 `ValidationResult`）。保留 `datasource_id` 参数与方案书对齐（当前校验与方言无关，属多方言校验预留）——在报告中说明
   - `execute_sql(datasource_id, sql)` → `QueryResult`：**防御性实现，顺序固定：内部 `validate` → `enforceLimit(max-limit)` → 适配器执行**（不依赖模型先调 validate_sql；课题硬性要求"只读 100% 生效"）。校验失败的策略二选一（抛 `BusinessException` 或返回带错误信息的结果）并在报告说明理由；推荐抛 `BusinessException`，让拒绝原因能被 Agent 学到
   - 适配器解析照抄已迁移的 `MetadataService` 写法：`dataSourceService.getById(id)` → `dataSourceFactory.getAdapter(id, ds.getType(), ds.toAdapterConfig())`；无效 datasource_id 的报错风格与其保持一致
   - 所有 `@Tool`/`@ToolParam` 描述用中文、面向 LLM 写明用途与示例
2. `concurrencySafe` 逐工具评估（读类操作合理设 true，execute_sql 自行判断并说明）。
3. 验证（对应 W2 检查点"工具可独立调用"）：
   - `NL2SQLToolsTest`（集成，直连本机 MySQL）：直接实例化后逐个调用 5 个工具并断言（不经过 LLM）；目标数据源按 `database_name='nlp2sql_test'` 查询（**不硬编码 id**）
   - `ToolkitRegistrationTest`：`registerTool` 后 `getToolNames()` 恰含 5 个名字；`getToolSchemas()` 非空且参数 schema 含 `datasource_id` 等
   - 安全兜底用例：直接调 `execute_sql` 传 DELETE / 堆叠注入 → 被拒；无 LIMIT 的 SELECT → 自动附加 LIMIT（断言行数 ≤ max-limit）
4. 不改 T2/T3/T7 已迁移文件；确有小 bug 才最小修并在报告说明。

## 硬约束

- 不建空包、空文件；**不实现 Agent 装配（T5）与任何 Controller/SSE**。
- git：只 `git add backend/` 下本任务路径；**禁止 `git add -A` / `git add .`**；中文 conventional commit，2–3 个提交（实现 / 注册与独立调用测试 / 安全兜底测试）。
- 工作树中非本任务文件不要动。

## 验证（实际执行，真实输出进报告）

1. `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package` 通过；贴 surefire `Tests run` 行（已有 21 例 + 新增）。
2. 报告中给出 5 个工具对 `nlp2sql_test` 的真实调用摘要（表数量、某表列数、样例行数、校验通过/拒绝结果）。

## 报告要求（写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T4-report.md`）

- 文件清单；验证命令真实输出关键行
- 5 工具调用样例（输入 → 输出摘要）
- 三项决策说明：readOnly/concurrencySafe 取值、validate_sql 的 datasource_id 用途、execute_sql 校验失败策略
- 偏差与自主决策；提交 hash；遗留问题（供 T5 Agent 装配参考）
