# T22 派发说明 — HDFS 文件适配器（WebHDFS + H2 内存表）

你是实现子代理，只依据本文件执行，不读其他计划文档。完成后写报告到 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T22-report.md`。

## 背景与已定方案

W4 环境已就绪（`docker/hive-hdfs/README.md`）：WebHDFS 在 `http://localhost:9870`，`/demo` 目录下有 `hive_orders.csv`（200 行，7 列，城市中文）与 `hive_products.csv`（20 行）。

**已与用户定案的设计**：裸 CSV 文件 → **WebHDFS 拉取 + H2 内存表执行 SQL**（直连 HDFS 不经过 Hive；表名 = 文件名去 `.csv`；SQL 中的表引用用 JSqlParser 提取后按需拉取对应文件）。H2 临时库用完即弃，不做复杂缓存（文件很小；如需缓存按 path+mtime 记，报告说明）。

**预判的两个坑（直接按此处理，勿重新摸索）**：
1. **WebHDFS OPEN 的 307 重定向**：响应 `Location` 是 `http://datanode:9864/...`（容器主机名），宿主机不可解析。处理：跟随重定向时把 **Location 的 host 替换为适配器配置的 host**（端口 9864 内外一致、已发布到宿主机）。实现处加注释，报告记录该决策。
2. **中文编码**：city 列是中文；H2 `CSVREAD` 注意 charset 选项（UTF-8），必须用含中文的断言验证往返正确。

## 交付物

1. `adapter/hdfs/HdfsFileAdapter.java` + `HdfsFileAdapterFactory.java`：
   - **直接实现 `DataSourceAdapter` 顶层接口**（非 JDBC，不继承 JdbcDataSourceAdapter）——先读接口与现有实现，保持方法语义一致；`getDialect()` 返回什么自行决定并在报告说明
   - 类型注册：code `hdfs`、displayName `HDFS 文件`、defaultPort 9870
   - 配置语义：`host`+`port` → WebHDFS 基址 `http://{host}:{port}/webhdfs/v1`；`database_name` → 根目录（如 `/demo`）；`username` → WebHDFS `user.name` 参数（缺省 `hadoop`）；encoding/分隔符本任务固定 UTF-8/逗号，报告说明
   - 方法实现：
     - `testConnection`：LISTSTATUS 根目录 → 200
     - `getTables`：LISTSTATUS 根目录 → `*.csv` 文件 → `TableInfo`（name=去扩展名；comment 可放文件大小/时间）
     - `getColumns(table)`：OPEN 拉首行 → 表头 split → `ColumnInfo`（类型可先统一 VARCHAR 或做简单推断，报告说明）
     - `executeQuery(sql, limit)`：JSqlParser 提取表名 → 逐表拉取到临时文件 → H2 内存库（`jdbc:h2:mem:...;DB_CLOSE_DELAY=-1`）建表（`CREATE TABLE ... AS SELECT * FROM CSVREAD(...)`）→ 执行用户 SQL → `ResultSet` → `QueryResult`；`Statement.setMaxRows(limit)` 兜底
2. pom 增补 `com.h2database:h2`（版本由 Boot parent 管）。
3. **演示数据源入库**：`nlp2sql_meta.datasources` 增加（type=hdfs, host=localhost, port=9870, database_name=/demo, name=`HDFS 演示目录`，password 空）；幂等；报告 id。
4. 集成测试 `HdfsFileAdapterIntegrationTest`（`assume` 守卫 localhost:9870）：
   - 以新入库数据源走 `DataSourceFactory`：`getTables` 含 hive_orders/hive_products；`getColumns(hive_orders)` 7 列
   - `executeQuery("SELECT city, COUNT(*) AS cnt FROM hive_orders GROUP BY city", 100)` → 5 个城市、各 40（断言含中文城市名，验证编码）
   - `executeQuery("SELECT * FROM hive_orders", 10)` → ≤10 行（LIMIT 兜底）
   - **Agent 端到端 1 例**（真实本地 7B，与 T21 同模式）：对 HDFS 数据源提问"订单文件里每个城市分别有多少订单？"→ 断言回答含城市名或 40
5. 不改 T2-T21 既有文件（新增为主；工厂注册如涉及共享文件属预期，报告说明）。

## 硬约束

- **真实 LLM 预算：≤2 次问答**；模型行为问题最多修 1 次，不无限调参。
- 不实现前端；不改 Hive/HDFS 容器配置（如确需改 compose 才能跑通，**停下来在报告里写清楚再改**，最小改动）。
- git：只 `git add backend/` 本任务路径；**禁止 `git add -A` / `git add .`**；中文 conventional commit，2–3 个提交。
- 工作树中非本任务文件不要动。

## 验证（实际执行，真实输出进报告）

1. `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package`：56 例 + 新增全绿（贴 surefire 行）。
2. 报告贴：WebHDFS 拉取与重定向处理的实际过程（可截取关键日志）；getTables/分组查询真实输出（含中文城市名）；Agent 端到端轨迹摘要。

## 报告要求（写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T22-report.md`）

- 文件清单；适配器关键决策（重定向 host 替换、H2 建表方式、类型推断策略、encoding 处理）
- 数据源记录 id 与幂等逻辑；验证命令真实输出关键行
- 偏差与自主决策；提交 hash；遗留问题（供 T23/W4 收尾）
