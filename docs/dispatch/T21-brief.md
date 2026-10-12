# T21 派发说明 — HiveAdapter（JDBC → HiveServer2）

你是实现子代理，只依据本文件执行，不读其他计划文档。完成后写报告到 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T21-report.md`。

## 背景

W4 环境已就绪并验证（见 `docker/hive-hdfs/README.md`）：本机 HiveServer2 在 `localhost:10000`，`demo` 库有 `orders`（200 行，5 状态各 40）与 `products`（20 行）两张外表，数据在 HDFS `/demo` 下。

现有适配器体系（T3 迁移）：`com.nl2sql.adapter` 下 `DataSourceAdapter`（接口）/`JdbcDataSourceAdapter`（HikariCP 基类，通用查询与元数据）/`MySQLAdapter`/`PostgreSQLAdapter`（方言参考，PostgreSQLAdapter 最简、通用元数据复用）/`DataSourceAdapterFactory`（SPI）+ 两个注册工厂 + `DataSourceTypeInfo`。`DataSourceFactory` 为注册表。**先读这些文件再动手**，HiveAdapter 按同一模式接入，不改动既有类（除必要的注册扩展）。

## 交付物

1. **依赖**：pom 增加 Hive JDBC 驱动。优先尝试 `org.apache.hive:hive-jdbc:4.0.1` 的 **standalone classifier**（单 jar 免依赖地狱）；若与 Spring Boot 类路径冲突（如 servlet-api/slf4j），退回全套依赖 + 精确 exclusions——两条路都试，报告写最终选择与理由（含冲突排查过程）。
2. `adapter/jdbc/HiveAdapter.java` + `HiveAdapterFactory.java`：
   - 类型注册：code `hive`、displayName `Hive`、defaultPort 10000
   - JDBC URL：`jdbc:hive2://{host}:{port}/{database}`（database 可为空则不带）
   - 元数据与查询复用基类/PostgreSQLAdapter 模式；如 Hive 元数据需要特殊处理（catalog/schema 语义、无主键等），做最小方言适配并在报告说明
   - HikariCP 与 hive-jdbc 兼容性处理（如 connectionTestQuery 等），有调整须说明
3. **演示数据源入库**：`nlp2sql_meta.datasources` 增加一条（type=hive, host=localhost, port=10000, database_name=demo, name=`Hive 演示库`，password 空）；**幂等**（先查同名再插），报告新记录 id。
4. **集成测试** `HiveAdapterIntegrationTest`（沿用既有直连真实环境风格）：
   - `assume` 守卫：`localhost:10000` 可达才执行（参照 `AgentE2ESupport.assumeLocalStackUp` 的端口探测写法；Hive 未启动则跳过，保证无环境构建通过）
   - 适配器路径（不经 LLM）：通过 `DataSourceFactory` 用**新入库数据源的 id** 取适配器 → `getTables()` 非空且含 orders/products → `getColumns("orders")` 非空 → `executeQuery("SELECT status, COUNT(*) AS cnt FROM orders GROUP BY status", 100)` 返回 5 行且每行 cnt=40 → 连接测试
   - **Agent 端到端 1 例**（真实本地 7B，1 次问答预算）：经 `ChatService.ask` 或直接装配 Agent，对 Hive 数据源提问（如"订单表里各状态分别有多少条？"），断言回答含真实数据（40 / 五个状态之一）
5. 不改 T2-T12 既有文件（新增为主；`DataSourceFactory` 若需注册调整属预期，报告说明）。

## 硬约束

- **真实 LLM 预算：≤2 次问答**（端到端 1 次 + 最多 1 次复验）；模型行为问题最多修 1 次，不无限调参。
- 不实现前端/其他数据源类型。
- git：只 `git add backend/` 本任务路径；**禁止 `git add -A` / `git add .`**；中文 conventional commit，2–3 个提交（依赖与适配器 / 集成测试 / 修复按需）。
- 工作树中非本任务文件不要动。

## 验证（实际执行，真实输出进报告）

1. `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package`：既有 53 例 + 新增全绿（贴 surefire 行）。
2. 报告贴：hive-jdbc 依赖选择的最终结论；适配器对 demo 库的真实调用输出（表清单、列清单、分组查询结果）；Agent 端到端轨迹摘要（工具序列 + 回答）。

## 报告要求（写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T21-report.md`）

- 文件清单；依赖选择与冲突处理过程
- 数据源记录 id 与幂等逻辑
- 验证命令真实输出关键行
- 偏差与自主决策；提交 hash；遗留问题（供 T22/T23）
