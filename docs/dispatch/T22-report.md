# T22 实施报告 — HDFS 文件适配器（WebHDFS 拉取 + H2 内存表）

派发说明：`docs/dispatch/T22-brief.md`。执行日期：2026-10-12。实施子代理按 brief 独立完成，未读其他计划文档。

## 一、完成摘要

- 新增 `hdfs` 数据源类型：裸 CSV 文件（HDFS）不经 Hive，经 WebHDFS 按需拉取 + H2 一次性内存表执行 SQL。
- 演示数据源入库 `nlp2sql_meta.datasources` **id=11**（`HDFS 演示目录`），幂等（复跑验证不重复）。
- 集成测试 3 例（类型注册免环境 / 适配器元数据与分组查询 / Agent 端到端），全量 `mvn -q clean package` 通过：**59 例 = 基线 56 + 新增 3，0 失败**（skip 明细见 §五）。
- Agent 端到端 1 次通过：`list_tables -> get_schema -> execute_sql`，回答"每个城市分别有 40 个订单。"
- 提交：`552f895`（feat，适配器+pom）、`2228322`（test，集成测试），共 2 个提交。

## 二、文件清单

| 文件 | 说明 |
|---|---|
| `backend/src/main/java/com/nl2sql/adapter/hdfs/HdfsFileAdapter.java` | 适配器主体（直接实现 `DataSourceAdapter`，非 JDBC 继承） |
| `backend/src/main/java/com/nl2sql/adapter/hdfs/HdfsFileAdapterFactory.java` | `@Component` 工厂：code=`hdfs`、displayName=`HDFS 文件`、defaultPort=9870、dialect=`h2` |
| `backend/pom.xml` | +`com.h2database:h2`（版本由 Boot parent 管，2.2.224） |
| `backend/src/test/java/com/nl2sql/adapter/HdfsFileAdapterIntegrationTest.java` | 集成测试 3 例 |
| `docs/dispatch/T22-report.md` | 本报告（按约束未随提交入库） |

未改 T2–T21 任何既有文件（除 pom.xml 增依赖，属 brief 交付物 2）。

## 三、适配器关键决策

### 1. WebHDFS OPEN 的 307 重定向：替换 Location 的 host（预判坑 1，按 brief 处理）

实测：`GET /webhdfs/v1/demo/orders/hive_orders.csv?op=OPEN` 返回 307，`Location: http://datanode:9864/webhdfs/v1/...&offset=0`——host 为容器名 `datanode`，宿主机不可解析（`curl -L` 直接 `exit=6 Could not resolve host`）。DataNode 9864 端口内外一致且已发布到宿主机。

实现：`HttpClient.Redirect.NEVER` 手动跟随；取 Location 后**替换 scheme/host 为适配器配置的 host，保留原 port 与 path/query**。适配器自身 debug 日志实证（3 次 OPEN = 3 次替换）：

```
DEBUG c.n.a.h.HdfsFileAdapter - WebHDFS 重定向 host 替换: datanode:9864 -> localhost:9864
```

回退路径：`http://localhost:9864/...` 拉取同一文件返回 200（curl `--resolve datanode:9864:127.0.0.1` 等价验证）。实现处（`HdfsFileAdapter#get`）带完整注释。

### 2. H2 建表方式：CSVREAD 读原始列 + CAST-select（含实测探针结论）

先用独立探针（H2 2.2.224 + 真实 CSV）摸清 CSVREAD 行为，结论：
- **CSVREAD 第二参数只按"列名列表"解析，不支持列类型**（写 `'order_id INT, ...'` 会变成名为 `order_id INT` 的单列）；也**没有 `skip` 选项**（`Feature not supported: "skip"`）；
- 不指定列名时首行即列名、**所有列都是 VARCHAR**（`SUM(amount)` 直接报 `90015 SUM or AVG on wrong data type`；对模型生成的聚合查询是硬伤）。

因此采用两段式（仍是 brief 指定的 CSVREAD 建表）：

```sql
CREATE TABLE "HIVE_ORDERS" AS SELECT
  CAST("ORDER_ID" AS BIGINT) AS "ORDER_ID", ...,
  CAST("AMOUNT" AS DECIMAL(20,6)) AS "AMOUNT",
  "STATUS", "CITY",
  CAST("ORDER_DATE" AS DATE) AS "ORDER_DATE"
FROM CSVREAD('<临时文件>', NULL, 'charset=UTF-8')
```

- 只有非 VARCHAR 列才包 CAST，VARCHAR 列直接引用（列名天然保留）；
- 大写引号标识符与 CSVREAD 生成的列名（H2 统一大写）、无引号 SQL 引用三者对齐；
- 若按推断类型建表失败（个别脏值无法转换），**自动退回全 VARCHAR 重试**，可用性优先。

### 3. 类型推断策略（getColumns 与建表共用一套）

采样前 ≤100 行数据（表头之外），按列取非空值，**"全列值均匹配才升级类型"**：
`-?\d+` → `BIGINT`（避开 INT 溢出）；`-?\d+(\.\d+)?` → `DECIMAL(20,6)`；`\d{4}-\d{2}-\d{2}` → `DATE`；其余/无值/混合 → `VARCHAR`。
getColumns 与 executeQuery 的推断输入一致（同一文件同一前 100 行），保证"给模型看的类型 == 执行时的类型"。实测：`order_id BIGINT / amount DECIMAL(20,6) / order_date DATE / status、city VARCHAR`。

### 4. 编码与分隔符

固定 UTF-8 / 逗号（本任务范围）：CSVREAD 显式 `charset=UTF-8`；行解析先剥 BOM。往返验证：分组查询返回 5 个中文城市名逐字匹配断言通过（`assertEquals(Set.of("北京","上海","广州","深圳","杭州"), actual)`），独立证据程序同样输出 `{CITY=上海, CNT=40}` 等。

### 5. 表发现：客户端递归（自主决策）

brief 预期 `/demo` 下平铺 CSV；**实测 CSV 在 `/demo/orders/hive_orders.csv` 与 `/demo/products/hive_products.csv` 两级子目录**。且该集群（Hadoop 3.4.1）WebHDFS `LISTSTATUS&recursive=true` 实测**只返回一层子目录、不展开子树**。故对根目录做客户端递归（逐层 LISTSTATUS，深度上限 5），表名仍=文件名去 `.csv`（目录不参与），不同目录同名 CSV 按路径排序取先者。

### 6. 连接与执行模型（无缓存，按 brief）

- `connect/testConnection`：LISTSTATUS 根目录 200 即通；`username` 空回退 `hadoop`；`password` 不使用。
- `executeQuery`：JSqlParser `TablesNamesFinder` 提取 SQL 表引用 → 归一化（去引号/去库名前缀/忽略大小写）匹配文件，**只拉被引用的表** → 临时文件 → 新建 `jdbc:h2:mem:nl2sql_hdfs_<uuid>;DB_CLOSE_DELAY=-1` → 执行（`setMaxRows(limit)` + 遍历处二次截断兜底）→ finally `DROP ALL OBJECTS` + `SHUTDOWN` 释放。不做跨查询缓存（文件极小；如需缓存按 path+mtime 记）。
- 失败语义对齐 JDBC 适配器：查询异常返回 `success=false + errorMessage` 不抛出；getTables/getColumns 失败返回空列表并记日志。
- **`getDialect()` 返回 `h2`**（SQL 实际由 H2 执行、LIMIT 等语法按 H2；工厂 `dialect()` 同步覆写为 `h2`）——brief 允许自行决定，在此说明。

## 四、数据源入库

```sql
INSERT INTO nlp2sql_meta.datasources (name, type, host, port, database_name, username, password, extra_config)
SELECT 'HDFS 演示目录', 'hdfs', 'localhost', 9870, '/demo', '', '', NULL
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM nlp2sql_meta.datasources WHERE name='HDFS 演示目录' AND type='hdfs');
```

- **新记录 id=11**：`name=HDFS 演示目录, type=hdfs, host=localhost, port=9870, database_name=/demo, username='', password=''`（username 留空以走"缺省 hadoop"代码路径；HEX(name)=UTF-8 核验通过）。
- 幂等实跑两次：第二次 `COUNT(*)=1` 无新增。集成测试按名称+类型定位（不硬编码 id）。

## 五、验证（真实输出）

### 1. 全量构建

`cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package`（jar 构建成功）surefire 汇总：

```
TOTAL run=59 fail=0 err=0 skip=3
com.nl2sql.adapter.HdfsFileAdapterIntegrationTest  run=3 fail=0 err=0 skip=0
```

- 59 = 基线 56（变更前实测 56/0/0/1）+ 新增 3；0 失败。
- 3 skip 中：`AgentCloudSmokeTest` 1（无云端 key，历史即有）；**另有 Hive 类 2 例 skip——当时 hive 容器因重启残留 pidfile 未起来（环境问题，见 §六），HDFS 相关用例不受影响**。随后已恢复 Hive 并单独复跑 Hive 非 LLM 用例全绿：`Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`（Hive 的 LLM 端到端不再重跑以保有 T22 预算）。

### 2. 适配器独立证据（直连已编译类，非 Spring 上下文）

```
== testConnection = true
== connect = true
== getTables ==
  hive_orders | 8542 字节 · 更新 2026-10-12 13:25:58 · /demo/orders/hive_orders.csv
  hive_products | 622 字节 · 更新 2026-10-12 13:25:58 · /demo/products/hive_products.csv
== getColumns(hive_orders) ==  order_id BIGINT / user_id BIGINT / product_id BIGINT / amount DECIMAL(20,6) / status VARCHAR / city VARCHAR / order_date DATE
== GROUP BY city ==  success=true rowCount=5
  {CITY=上海, CNT=40} {CITY=北京, CNT=40} {CITY=广州, CNT=40} {CITY=杭州, CNT=40} {CITY=深圳, CNT=40}
== SELECT * (limit=10) ==  success=true rowCount=10
== SUM 聚合 ==  success=true rows=[{STATUS=cancelled, CNT=40, TOTAL=41719.90}, ...]
```

### 3. 集成测试关键输出（clean package 的 surefire 记录）

```
[T22-hdfs] getTables() = [hive_orders, hive_products]
[T22-hdfs] getColumns(hive_orders) = [order_id, user_id, product_id, amount, status, city, order_date]
[T22-hdfs] columns=[CITY, CNT] rows=[{CITY=上海, CNT=40}, {CITY=北京, CNT=40}, {CITY=广州, CNT=40}, {CITY=杭州, CNT=40}, {CITY=深圳, CNT=40}]
[T22-hdfs] LIMIT 10 -> rowCount=10
```

### 4. Agent 端到端轨迹（真实本地 qwen2.5-coder:7b）

```
Q: 数据源ID为 11。这是 HDFS 上的订单文件数据源，hive_orders 表记录订单，请统计每个城市分别有多少订单。
工具调用序列: list_tables -> get_schema -> execute_sql
调用入参: {"datasource_id":11}
调用入参: {"datasource_id":11,"table_name":"hive_orders"}
调用入参: {"datasource_id":11,"sql":"SELECT city, COUNT(order_id) AS order_count FROM hive_orders GROUP BY city LIMIT 10"}
最终回答: 每个城市分别有 40 个订单。（耗时=5080ms 事件数=32）
```

- **LLM 预算**：共 2 次问答（单测首跑 1 次 + clean package 1 次），**均一次通过，未消耗"模型行为修复"额度**。

## 六、环境处理记录（无 compose 配置改动）

- 接管时宿主机 Docker Desktop 未运行（全部容器 Exited）：启动 Docker Desktop 后 `docker compose up -d` 拉起 Hive/HDFS，并 `docker start redis`。
- 首次拉起后 **hive 容器约 1 分钟后退出**：容器 /tmp 残留旧会话 pidfile（`HiveServer2 running as process 7. Stop it first.`），进程号恰好与新容器自身冲突。修复：`docker compose up -d --force-recreate hive`（重建容器即清 /tmp；metastore 在 bind mount 不受影响）。**未改 compose 文件**。重启宿主机 Docker 后如遇同样症状可照此恢复。
- 因此 clean package 运行时 Hive 尚未就绪（2 例 skip）；恢复后 Hive 非 LLM 用例已复跑全绿（§五.1）。

## 七、偏差与自主决策

1. **CSV 目录层级**：brief 预期 `/demo` 平铺，实际两级子目录 + `LISTSTATUS recursive=true` 不生效 → 客户端递归（§三.5）。
2. **建表 SQL 形态**：brief 写 `CREATE TABLE ... AS SELECT * FROM CSVREAD(...)`；实测该形态列全 VARCHAR、聚合不可用，改为"CSVREAD + CAST-select"（仍为 CSVREAD 机制，§三.2），并加类型推断失败回退。
3. **getColumns 拉取**：brief 说"拉首行"，实际一次拉 64KB 首段（表头 + 采样行），同时满足列名与类型推断两个目的。
4. **dialect=h2**（brief 授权自行决定并在报告说明，§三.6）。
5. username 入库为空串，显式走缺省 `hadoop` 路径（§四）。
6. 期间为验证 H2 行为在 `backend/target/` 下写了临时探针程序（`mvn clean` 时已清除，未入库）。

## 八、遗留问题（供 T23/W4 收尾）

- **无跨查询缓存**：每次 executeQuery 重新拉文件 + 重建 H2 内存库（演示文件 KB 级无感；如需优化按 path+mtime 缓存临时文件或库）。
- `DECIMAL(20,6)` 显示补零：`amount=187.13` 查询结果为 `187.130000`（可读性小瑕疵，如需可改按数据推断 scale）。
- 边界（演示数据未见，风险已知）：不同目录同名 CSV 取路径序先者；SQL 中以引号引用小写表名（`"hive_orders"`）会因 H2 大小写敏感报"表不存在"（无引号不受影响）；表头含空格/空列名等脏形态会走回退或失败。
- getColumns 的推断样本来自前 64KB；超宽表的极靠后列可能采样行偏少（与执行期推断输入基本一致，极端情况类型展示可能与建表不一致）。
- clean package 快照中 2 个 Hive 用例 skip（环境时序所致，非代码问题；环境已恢复，Hive 非 LLM 路径复跑绿，LLM 端到端沿用 T21 归档结果）——W4 收尾跑全量时以完整绿为准。
- 前端未涉及；`supportedTypes()` 会自动多出 `hdfs / HDFS 文件 / 9870` 供类型选择器渲染，W4 联调时可留意。

## 九、提交

| Hash | 提交 |
|---|---|
| `552f895` | feat(adapter): 新增 HDFS 文件适配器（WebHDFS 拉取 + H2 内存表执行 SQL） |
| `2228322` | test(adapter): HDFS 文件适配器集成测试与 Agent 端到端验证 |
