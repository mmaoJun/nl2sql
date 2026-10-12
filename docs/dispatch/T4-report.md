# T4 报告 — 5 个 @Tool 工具与独立调用验证（W2 检查点核心）

## 结论摘要

- `NL2SQLTools` 5 个工具（list_tables / get_schema / sample_data / validate_sql / execute_sql）全部实现，均为 `readOnly = true`、中文描述带调用示例；**不经过 LLM / Agent / Toolkit 逐个直接调用通过**（W2 检查点"工具可独立调用"达成）。
- `mvn -q clean package` 通过；surefire 共 **34 例全绿** = 已有 21 例 + 新增 13 例（5 + 4 + 4），无 skip。
- 真实连通本机 MySQL `nlp2sql_test`（数据源按 `database_name` 定位，实测 id=5）：25 张表、`users` 20 列、抽样默认 3 行且上限 100 行、合法 SELECT 校验通过 / DELETE 被拒。
- 安全兜底实测：DELETE、堆叠注入、UNION 注入、sample_data 注入表名全部被拒（错误码 2002），拒绝前后 `users` 行数 200 → 200 未变；无 LIMIT 的 SELECT 实际下发的是 `SELECT * FROM api_access_logs LIMIT 100`。
- T2/T3/T7 已迁移文件零改动；未改编译器配置；未实现 Agent 装配与任何 Controller/SSE。

## 文件清单

| 文件 | 说明 |
| --- | --- |
| `F:\FANG_ZHENG\nl2sql\backend\src\main\java\com\nl2sql\tool\NL2SQLTools.java` | 新增（270 行）：5 个 `@Tool` 方法 + 适配器解析 + 表名白名单 + 抽样行数归一化 |
| `F:\FANG_ZHENG\nl2sql\backend\src\test\java\com\nl2sql\tool\NL2SQLToolsTest.java` | 新增（203 行，5 个用例）：直连 MySQL 逐个调用 5 个工具 |
| `F:\FANG_ZHENG\nl2sql\backend\src\test\java\com\nl2sql\tool\ToolkitRegistrationTest.java` | 新增（144 行，4 个用例）：注册、schema 参数、readOnly/并发标志、Toolkit 调用链路 |
| `F:\FANG_ZHENG\nl2sql\backend\src\test\java\com\nl2sql\tool\NL2SQLToolsSecurityTest.java` | 新增（215 行，4 个用例）：execute_sql/sample_data 安全兜底 |
| `F:\FANG_ZHENG\nl2sql\docs\dispatch\T4-report.md` | 本报告（docs/ 未纳入版本控制，不随提交入库） |

只读参考、未改动：旧工程《技术方案书》§5.2 签名、`F:\FANG_ZHENG\nlp2sql` 其余代码；新工程 T2/T3/T7 全部文件。

## 验证记录（实际执行，真实输出）

### 1. `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package`

```
EXIT=0
产物: target/nl2sql-backend-1.0.0-SNAPSHOT.jar (76,481,476 B)
```

提交完成后又用 brief 的原命令 `mvn -q clean package` 复跑一次：EXIT=0，日志内 `ERROR` 出现 0 次，surefire 报告仍为 34 例全绿。

surefire 汇总（取 `Tests run` 行需去掉 `-q`，命令等价，下表为同一次全绿结果）：

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 11.31 s -- in com.nl2sql.adapter.DataSourceAdapterIntegrationTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.331 s -- in com.nl2sql.security.SqlSecurityCheckerTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.558 s -- in com.nl2sql.service.MetadataServiceIntegrationTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.280 s -- in com.nl2sql.tool.NL2SQLToolsSecurityTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.257 s -- in com.nl2sql.tool.NL2SQLToolsTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.129 s -- in com.nl2sql.tool.ToolkitRegistrationTest
[INFO] Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

34 = 已有 21（3 + 14 + 4）+ 新增 13（4 + 5 + 4），全部实际执行（Skipped: 0，MySQL 在跑未走 assume 跳过）。

> 输出编码说明：测试 JVM 的控制台编码为本机 GBK（Java 17 不支持 `-Dstdout.encoding`），以下中文行是 surefire 日志按 GBK 解码后转写；库里的中文数据本身是正确 UTF-8（`users.name` 十六进制 `E8B5B5E69CB1` = "赵朱"）。

### 2. 5 个工具调用样例（`[T4]` 真实输出行，输入 → 输出摘要）

| 工具 | 输入 | 输出摘要（实测） |
| --- | --- | --- |
| `list_tables` | `datasource_id=5` | `tables=25 sample=[addresses, api_access_logs, cache_statistics, categories, coupons]` |
| `get_schema` | `datasource_id=5, table_name=users` | `columns=20 sample=[id:int, name:varchar, email:varchar, phone:varchar, age:int]`，含主键列（断言 `columnKey='PRI'` 通过） |
| `sample_data` | `table_name=users, limit=null / 5 / 100000` | `default=3 rows, limit=5 -> 5 rows, limit=100000 -> 100 rows (max-limit=100), columns=20`；首行 `{id=1, name=赵朱, email=赵朱1@company.com, age=23, gender=女, title=高级工程师, salary=8100.00, ...}` |
| `validate_sql` | `SELECT id, name FROM users WHERE age > 30` | `valid=true`（errorMessage=null） |
| `validate_sql` | `DELETE FROM users WHERE id = 1` | `valid=false reason=包含禁止的SQL关键字: DELETE` |
| `execute_sql` | `SELECT id, name FROM users ORDER BY id LIMIT 5` | `rows=5 columns=[id, name] firstRow={id=1, name=赵朱} cost=0ms`，`success=true` |

原始行：

```
[T4] list_tables(datasource_id=5) -> tables=25 sample=[addresses, api_access_logs, cache_statistics, categories, coupons]
[T4] get_schema(table=users) -> columns=20 sample=[id:int, name:varchar, email:varchar, phone:varchar, age:int]
[T4] sample_data(table=users) -> default=3 rows, limit=5 -> 5 rows, limit=100000 -> 100 rows (max-limit=100), columns=20 sample=[{id=1, name=赵朱, ...}]
[T4] validate_sql -> SELECT: valid=true; DELETE: valid=false reason=包含禁止的SQL关键字: DELETE
[T4] execute_sql -> rows=5 columns=[id, name] firstRow={id=1, name=赵朱} cost=0ms
```

### 3. Toolkit 注册与 schema 自检（真实输出）

```
[T4] toolkit.getToolNames()=[validate_sql, sample_data, execute_sql, list_tables, get_schema]
[T4] schema list_tables  required=[datasource_id] properties=[datasource_id]
[T4] schema get_schema   required=[datasource_id, table_name] properties=[datasource_id, table_name]
[T4] schema sample_data  required=[datasource_id, table_name] properties=[datasource_id, limit, table_name]
[T4] schema validate_sql required=[datasource_id, sql] properties=[datasource_id, sql]
[T4] schema execute_sql  required=[datasource_id, sql] properties=[datasource_id, sql]
[T4] flags readOnly=true for all 5; concurrencySafe: list_tables/get_schema/sample_data/validate_sql=true, execute_sql=false
[T4] toolkit.call validate_sql -> ok=[{"valid":true,"errorMessage":null}]; rejected=[{"valid":false,"errorMessage":"包含禁止的SQL关键字: DELETE"}]
```

- 工具名恰为 5 个（`assertEquals(EXPECTED_TOOLS, names)`）。
- `parameters.get("type")=="object"`、`properties` 为 Map 且含 `datasource_id`；`sample_data` 的 `limit` 在 properties 但**不在 required**（可选参数反射正确）；`limit` 未传时默认 3 由工具内部归一化。
- 通过 Toolkit 完整调一次 `validate_sql`：入参按名字绑定成功、返回记录 `ValidationResult` 被序列化为 JSON 回传——证明"参数名保留 + Jackson 记录序列化"整条反射链路可用（`-parameters` 生效，编译器配置未改）。
- 直调 `AgentTool.callAsync` 时返回块 `state` 停在 `RUNNING`（由上层 Agent 运行时收尾），测试只断言内容不断言状态。

### 4. 安全兜底实测（真实输出）

```
[T4] execute_sql(DELETE) -> rejected code=2002 message=SQL 未通过安全校验: 包含禁止的SQL关键字: DELETE; rows before=200 after=200
[T4] execute_sql(stacked) -> rejected: SQL 未通过安全校验: 包含禁止的SQL关键字: DROP; execute_sql(union) -> rejected: SQL 未通过安全校验: 检测到可疑的 UNION 查询
[T4] sample_data(注入表名) -> rejected: 非法的表名: users; DROP TABLE users，只允许普通标识符（字母/数字/下划线，可带库名前缀）
[T4] execute_sql(无 LIMIT) -> rows=100 max-limit=100 enforcedSql=[SELECT * FROM api_access_logs LIMIT 100]; max-limit=3 -> rows=3
```

- DELETE 拒绝：`BusinessException` 错误码 2002（`SQL_VALIDATION_FAILED`），原因含关键词；**拒绝前后 users 行数 200 → 200**，证明语句真的没执行。
- 堆叠注入 `...; DROP TABLE users`、UNION 注入均被拒；目标表完好。
- 自动 LIMIT：用记录型校验器断言 execute_sql 实际交给适配器的 SQL 就是 `SELECT * FROM api_access_logs LIMIT 100`（配置 max-limit=100）；已带 LIMIT 的语句原样保留不重复追加；把上限收到 3 时，2000 行（`count(*)` 直查）的 `api_access_logs` 只回 3 行。

### 5. 环境

```
netstat -an | grep -c ":6379"  → 0（Redis 未运行，MetadataService 走 T7 的直连降级，工具响应不受影响）
netstat -an | grep ":3306"     → TCP 0.0.0.0:3306 LISTENING（本机 MySQL 在跑）
nlp2sql_meta.datasources: id=5, type=mysql, database_name=nlp2sql_test（测试按库名查询定位，未硬编码 id）
```

## 三项决策说明

### 决策 1：readOnly / concurrencySafe 取值

| 工具 | readOnly | concurrencySafe | 理由 |
| --- | --- | --- | --- |
| `list_tables` | true | **true** | 纯元数据读，无参数拼接，适配器每次取独立连接 |
| `get_schema` | true | **true** | 同上；表过滤在内存 List 上做，不进 SQL |
| `sample_data` | true | **true** | 自带 LIMIT 的只读抽样，结果行数有上限；与其他读操作无共享可变状态 |
| `validate_sql` | true | **true** | 无副作用的纯函数，天然并行安全 |
| `execute_sql` | true | **false** | 见下 |

`readOnly = true` 五个一致：全部只读、无写库路径。`execute_sql` 取 `concurrencySafe = false` 的理由：只读不等于便宜——执行的是模型现编的任意 SQL，可能全表扫描大表；AgentScope 2.0.2 的 `ToolExecutor` 会把 `concurrencySafe=true` 的调用聚成并行批次，取 false 可避免同一轮里多条重查询同时压向目标数据源（每数据源连接池上限 5），同时让"校验 → 执行"的日志顺序保持确定。代价只是多条 execute_sql 串行，延迟损失可接受。

### 决策 2：validate_sql 的 datasource_id 用途

保留该参数与《技术方案书》§5.2 签名对齐，**当前不参与校验**：`SqlSecurityChecker` 是纯文本三层校验（关键词黑名单 / JSqlParser AST / 注入模式），与方言无关，加不加这个 id 结果一样。工具描述里对模型写明了"校验只看 SQL 文本、datasource_id 仅为签名对齐保留"，避免模型以为换个 id 会改变结论。实现里仅 `log.debug` 记录该值以免参数被静默丢弃。将来做多方言差异化校验（例如按 `DataSourceFactory.getDialect(type)` 加方言语法检查）时，这个参数就是入口，无需再改签名——改签名会让 T5 的 prompt 与已上线会话失效。

### 决策 3：execute_sql 校验失败策略 —— 抛 BusinessException（选抛，不选返回错误结果）

- 顺序固定为 `validate → enforceLimit(max-limit) → 适配器执行`，不依赖模型先调 `validate_sql`；`enforceLimit` 在校验之后，保证即使原 SQL 带 LIMIT 也统一受 max-limit 约束。
- 失败抛 `BusinessException(SQL_VALIDATION_FAILED=2002, "SQL 未通过安全校验: <原因>")`，理由：
  1. **拒绝原因能被 Agent 学到**。AgentScope 的 `ToolMethodInvoker` 给工具调用挂了 `onErrorResume`（javap 复核字节码确认），异常会转成工具错误结果回传模型、不会打断 Agent 循环；实测 `toolkit.call` 的返回内容会原样进模型上下文（`validate_sql` 拒绝时回传 `{"valid":false,"errorMessage":"包含禁止的SQL关键字: DELETE"}`）。T5 装配后建议端到端再确认一次异常路径真的能被模型读到并修正。
  2. **失败语义唯一**，不产生"看似成功的结果对象"：`QueryResult` 里既有 `success` 又有 `errorMessage`，若用它承载"被安全校验拒绝"，调用方容易只看 `rows` 而忽略拒绝，且 2002 与 2003（执行失败）会混在一个对象里难以区分。
  3. 与数据源不存在的报错风格一致（`DataSourceService.getById` 也抛 `BusinessException`），工具层对外只有一种失败形态。
- `sample_data` 复用同一策略（表名非法 / 构造 SQL 校验失败都抛 2002）。

## 偏差与自主决策

1. **额外加了一层表名白名单**（`[A-Za-z0-9_$]+(\.[A-Za-z0-9_$]+)?`）：brief 只要求"构造的 SQL 必须先过 SqlSecurityChecker.validate"，已照做；考虑到表名是唯一由模型文本直接参与 SQL 拼接的入口，再叠一层标识符校验，拦截 `users; DROP ...`、注释符等形态，避免"校验器未来放宽"或"backtick 包住后 L1 扫描被剥离"这类边界绕过。判定前对表名 `trim`（仅容忍首尾空白，SQL 里拼的仍是原文，无注入面；`matches()` 要求整串匹配，空白/斜杠/引号等一律被拒）。代价是带空格/连字符的罕见表名会被拒（见遗留问题 5）。
2. **`execute_sql` 取 `concurrencySafe = false`**：brief 交给自行判断，理由见决策 1。
3. **测试里"直接实例化"按字面执行**：`NL2SQLToolsTest` / `NL2SQLToolsSecurityTest` 用注入的 collaborators 直接 `new NL2SQLTools(...)` 再逐个调方法（严格不经过 Toolkit）；`ToolkitRegistrationTest` 是纯 JVM 单测，注册时只传真实 `SqlSecurityChecker`、另三个 collaborator 传 `null`——注册走注解反射不触达数据库，若误触达会直接 NPE，等于顺带证明"注册不依赖库连接"。
4. **`max-limit` 单值双用**：同时作为 `enforceLimit` 的追加值和 `executeQuery(sql, limit)` 的行数上限（都与配置 `nl2sql.sql-security.max-limit` 绑定），避免"SQL 里写 100、内存里截 50"这种不一致。
5. **AgentScope API 核实**：写码前用 `javap -cp` 复核了本地 jar——`@Tool` 确实有 `name/description/strict/readOnly/concurrencySafe/externalTool/stateInjected/dangerousFiles/dangerousDirectories/converter`，`concurrencySafe` 注解默认值是 **true**（`readOnly` 等默认 false），代码里 5 个工具的四个关键标志全部显式写出，不依赖默认值；schema 由 victools JSON-Schema 生成，`getToolSchemas()` 的 `parameters` 是 `{type, properties, required}` 结构。
6. **失败后不改测试而是改断言**：`ToolkitRegistrationTest` 起初断言 Toolkit 调用的返回状态是 `SUCCESS`，实测直调 `AgentTool.callAsync` 停在 `RUNNING`（状态由上层 Agent 运行时收尾）；这是 API 事实而非缺陷，改为断言回传内容（`valid`/拒绝原因）这种真正要保证的行为。
7. 未改任何 T2/T3/T7 文件，未发现需要最小修的小 bug；未改编译器/编译配置；未建空包/空文件；未实现 Agent 装配（T5）与 Controller/SSE（不在本任务）。

## 提交

| Hash | 说明 |
| --- | --- |
| `9f9707f` | feat(tool): 新增 5 个只读 @Tool 工具与安全兜底执行链路（1 文件，+270） |
| `4d528a3` | test(tool): 新增工具注册与 5 工具独立调用集成用例（2 文件，+347） |
| `ab74755` | test(tool): 新增 execute_sql 安全兜底用例（1 文件，+215） |

仅 `git add backend/` 下本任务 4 个路径（未用 `git add -A` / `.`）；提交后 `git status --short` 只剩既有未跟踪项 `docs/` 与课题 txt，本任务工作树无残留。

## 遗留问题（供 T5 Agent 装配参考）

1. **装配方式**：`new Toolkit().registerTool(nL2SQLToolsBean)` 即可（实测工具名 = 5 个 `@Tool(name)` 的 snake_case，方法名 camelCase 不暴露给模型）；无需任何适配层。Bean 只依赖 `MetadataService`/`DataSourceService`/`DataSourceFactory`/`SqlSecurityChecker`，均已就绪。
2. **失败面**：工具失败统一抛 `BusinessException`（2002 校验失败、1001 数据源不存在、1002 连接失败等），经 `ToolMethodInvoker.onErrorResume` 以错误结果回传模型；建议 T5 端到端再确认模型真能读到并修正。
3. **返回体积**：`execute_sql` 已由 max-limit（100 行）与适配器列数共同约束，但宽表 × 100 行仍可能过大；若 prompt 超预算，T5 侧需自行摘要，工具层未做行数/字符截断（未来可加）。
4. **`sample_data` 的失败形态有两层**：安全层面（表名非法、SQL 校验失败）抛 `BusinessException`；"表不存在"等执行层面错误则由适配器包成 `QueryResult(success=false, errorMessage=...)` 返回（不抛异常），模型需读 `success`/`errorMessage` 判断。T5 侧若做统一错误处理，注意这个两层结构。
5. **`sample_data` 表名白名单偏严**：只接受普通标识符（可带库名前缀），带空格/连字符的表名会被拒；如需放宽应改为"表名必须在 `getTables` 结果中"的元数据校验，而不是放松正则。
6. **多方言**：`validate_sql` / `enforceLimit` 当前对方言无感知（`LIMIT` 对 PostgreSQL 同样成立）；接 Oracle/SQL Server 等需在工具内按 `ds.getType()` 分支，`validate_sql` 的 `datasource_id` 就是为此预留（见决策 2）。
7. **数据源记录是环境态**：`nlp2sql_test` 记录（id=5）不经仓库 SQL 脚本创建，换机器需补；测试查不到会以明确提示断言失败，不会静默跳过。Redis 未运行 → 元数据走 T7 的直连降级，功能不受影响。
8. **无 token 预算控制**：`SchemaCompressor`（T7）是 prompt 侧的压缩入口，本任务 5 个工具返回的都是原始结构；T5 组装 system prompt 时应继续走 `SchemaCompressor`，不要直接把 `get_schema` 的全列塞进上下文。
9. surefire 每次会在 `target/surefire-reports/` 生成一个 `.dumpstream` 警告（surefire-booter 含绝对路径 + "'other' has different root"），为已有环境噪声，非测试失败。
