# T3 报告 — 数据源适配体系与持久化迁移

- 状态：完成
- 提交：`04c462f`（adapter）、`502c1f3`（持久化与配置 + pom + sql）、`ecb70db`（集成测试）
- 基线：`b97e2df`（T2）；旧仓库 `F:\FANG_ZHENG\nlp2sql` 全程只读，工作树保持干净（`git status --porcelain` 无输出）

## 1. 文件清单

### 新增 — 适配器层（commit `04c462f`，12 文件）

| 文件 | 说明 |
| --- | --- |
| `backend/src/main/java/com/nl2sql/adapter/DataSourceAdapter.java` | 顶层接口，公开 API 与旧版一致 |
| `backend/src/main/java/com/nl2sql/adapter/DataSourceAdapterFactory.java` | SPI 工厂接口 |
| `backend/src/main/java/com/nl2sql/adapter/DataSourceFactory.java` | 注册表 + 适配器缓存 + testConnection |
| `backend/src/main/java/com/nl2sql/adapter/DataSourceTypeInfo.java` | 类型元数据 record |
| `backend/src/main/java/com/nl2sql/adapter/jdbc/JdbcDataSourceAdapter.java` | HikariCP 基类（连接/元数据/查询） |
| `backend/src/main/java/com/nl2sql/adapter/jdbc/MySQLAdapter.java` | INFORMATION_SCHEMA 方言元数据 |
| `backend/src/main/java/com/nl2sql/adapter/jdbc/MySQLAdapterFactory.java` | mysql 类型注册（3306） |
| `backend/src/main/java/com/nl2sql/adapter/jdbc/PostgreSQLAdapter.java` | schema 参数 + 通用元数据复用 |
| `backend/src/main/java/com/nl2sql/adapter/jdbc/PostgreSQLAdapterFactory.java` | postgresql 类型注册（5432） |
| `backend/src/main/java/com/nl2sql/model/TableInfo.java` | 适配器值对象（随 adapter 提交以保证编译独立） |
| `backend/src/main/java/com/nl2sql/model/ColumnInfo.java` | 同上 |
| `backend/src/main/java/com/nl2sql/model/dto/QueryResult.java` | 同上 |

### 新增 — 持久化 / 服务 / 配置（commit `502c1f3`，11 文件 + 1 修改）

| 文件 | 说明 |
| --- | --- |
| `backend/src/main/java/com/nl2sql/model/DataSource.java` | 实体，`toAdapterConfig()` 合并 extraConfig |
| `backend/src/main/java/com/nl2sql/model/dto/DataSourceDTO.java` | 创建/更新请求体 |
| `backend/src/main/java/com/nl2sql/model/vo/DataSourceVO.java` | 视图对象，密码脱敏 `******` |
| `backend/src/main/java/com/nl2sql/mapper/DataSourceMapper.java` | MyBatis-Plus BaseMapper |
| `backend/src/main/java/com/nl2sql/service/DataSourceService.java` | CRUD + 连接测试 + 类型校验（与原实现逐字一致，仅包名替换） |
| `backend/src/main/java/com/nl2sql/config/RedisConfig.java` | RedisTemplate 序列化配置 |
| `backend/src/main/java/com/nl2sql/config/MyBatisPlusConfig.java` | 分页插件 + 时间字段自动填充 |
| `backend/pom.xml` | **修改**：增补 `commons-pool2`（照抄旧 pom 写法，版本由 Boot parent 管理，位于 data-redis 之后） |

### 新增 — SQL 脚本（commit `502c1f3`，逐字复制，`cmp` 全部 IDENTICAL）

- `sql/init_backend.sql`（4342 B）
- `sql/sample_data/generate_test_data.sql`（84308 B，25 表测试数据生成）
- `sql/sample_data/mysql_sample.sql`（5550 B）
- `sql/sample_data/postgresql_sample.sql`（6602 B）

### 新增 — 测试（commit `ecb70db`）

- `backend/src/test/java/com/nl2sql/adapter/DataSourceAdapterIntegrationTest.java`

未改动 T1/T2 契约类；未新建空包/空文件；未触碰 `docs/`、`实习生课题(1).txt` 等工作树未提交文件。

## 2. 验证（真实输出）

### 2.1 `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package`

```
MVN_EXIT=0
```

surefire 报告（`target/surefire-reports/*.txt`）：

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 11.47 s -- in com.nl2sql.adapter.DataSourceAdapterIntegrationTest
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.227 s -- in com.nl2sql.security.SqlSecurityCheckerTest
```

0 Skipped 说明集成用例真实跑完（未走 assume 跳过）。关键日志行：

```
DEBUG c.n.m.DataSourceMapper.selectList - ==> Preparing: SELECT id,name,type,host,port,database_name,username,password,extra_config,created_at,updated_at FROM datasources WHERE (type = ?) ORDER BY id ASC LIMIT 1
DEBUG c.n.m.DataSourceMapper.selectList - ==> Parameters: mysql(String)
DEBUG c.n.m.DataSourceMapper.selectList - <==      Total: 1
INFO  com.zaxxer.hikari.HikariDataSource - HikariPool-2 - Start completed.        （适配器/临时池连上 nlp2sql_sample）
INFO  com.zaxxer.hikari.HikariDataSource - HikariPool-2 - Shutdown completed.     （disconnect/removeAdapter 正常释放）
... Started DataSourceAdapterIntegrationTest in 8.405 seconds (process running for 9.291)
```

产物：`target/nl2sql-backend-1.0.0-SNAPSHOT.jar`（76,468,818 字节）生成成功。

集成用例断言链：按类型取首条 mysql 记录（实际命中 id=1，库 `nlp2sql_sample`，未硬编码 id）→ `DataSourceFactory.getAdapter` 取适配器 → `getTables()` 非空 → `getColumns(首表)` 非空 → `SELECT * FROM <首表> LIMIT 5` success、列非空、样例行非空、行数 ≤ 5 → `DataSourceFactory.testConnection` 与 `DataSourceService.testConnection(id)` 均 true → 注册表断言（mysql / MySQL / 3306 / MySQLAdapter 实例）。

### 2.2 `ls F:\FANG_ZHENG\nl2sql\sql`

```
F:/FANG_ZHENG/nl2sql/sql:
init_backend.sql            (4342 B)
sample_data/

F:/FANG_ZHENG/nl2sql/sql/sample_data:
generate_test_data.sql      (84308 B)
mysql_sample.sql            (5550 B)
postgresql_sample.sql       (6602 B)
```

逐字复制校验（源目录 `cmp`）：

```
IDENTICAL: init_backend.sql
IDENTICAL: sample_data/generate_test_data.sql
IDENTICAL: sample_data/mysql_sample.sql
IDENTICAL: sample_data/postgresql_sample.sql
```

## 3. 解耦项与 TODO 清单

对全部迁移来源文件 grep `AuditContext|AuditService|Jwt|SecurityContext|UserContext`：**命中 0**。旧 `DataSourceService` / 各工厂 / 实体 / 配置均未引用用户、审计、安全组件（旧 `DataSourceController` 亦无），因此：

- 无任何调用被裁剪，**未插入 `// TODO(W4 安全任务)` 行**（brief 中该规则为条件触发，条件未成立）。
- 逐文件确认无耦合：`DataSourceAdapter`、`DataSourceAdapterFactory`、`DataSourceFactory`、`DataSourceTypeInfo`、`JdbcDataSourceAdapter`、`MySQLAdapter(+Factory)`、`PostgreSQLAdapter(+Factory)`、`DataSource`、`TableInfo`、`ColumnInfo`、`DataSourceDTO`、`QueryResult`、`DataSourceVO`、`DataSourceMapper`、`DataSourceService`、`RedisConfig`、`MyBatisPlusConfig`。

供 W4 参考：`DataSourceService.create/update/delete/list` 无归属校验与审计（与旧版一致），若 W4 要求按用户隔离数据源，需在此层补 owner 过滤。

## 4. 偏差与自主决策

1. **提交拆分**：`TableInfo/ColumnInfo/QueryResult` 归入 adapter 提交（否则第一条提交无法独立编译）；`sql/` 复制并入"持久化与配置"提交，使提交数保持 3 个（brief 上限）。
2. **测试命名**：沿用旧仓库注记，命名 `*Test` 而非 `*IT`，确保 surefire 默认收测；`@SpringBootTest` 引导全上下文（沿用旧 `ChatSessionMapperTest` 风格：socket 探活 + 未启动时 assume 跳过）。
3. **断言选表**：只读查询对 `getTables()` 首表（nlp2sql_sample.departments，5 行）执行并断言样例行非空；若样例库被清空该断言会红。
4. **未迁移文件**：`DataSourceController`、`MetadataService`、`SchemaCompressor`、`AuditService`、`SecurityConfig`、`WebConfig`、`Jwt*` 均不在迁移表内，未迁移（控制层/元数据服务留给后续任务）。
5. **application.yml 未改动**：redis / mybatis-plus / schema-cache 配置 T1 已就位；`commons-pool2` 补入后 lettuce pool 配置生效。
6. 测试类中保留 `factoryRegistersMysqlTypeMetadata`（纯注册表断言、不依赖数据），与其余两条集成用例同文件。

## 5. 遗留问题（供 T4 / T7）

1. **适配器缓存语义**（旧版行为已保持）：`DataSourceFactory.getAdapter` 在 `connect()` 返回 false 时仍会缓存未连接实例。T4 工具层取适配器后建议检查连接有效性或失败即 `removeAdapter` 重试。
2. **明文密码边界**：`DataSourceService.getById` 返回含明文密码实体；对外出口必须走 `DataSourceVO`（已脱敏）。
3. **PostgreSQL 未真机验证**：适配器与工厂已迁移，`nlp2sql_meta.datasources` 存在 pg_demo 记录，但本任务按 brief 只对本机 MySQL 做集成验证。
4. **T7 地基**：`TableInfo/ColumnInfo/QueryResult` 已就位，`nlp2sql.schema-cache.ttl-seconds: 3600` 配置已存在；MetadataService / SchemaCompressor 未迁移。
5. **Redis 未在测试中实连**：`RedisConfig` 已迁移且 RedisTemplate bean 懒建连，集成测试不依赖 Redis 进程；运行时首次访问缓存才暴露连接问题。
6. `DataSourceService.create/update/delete/list` 暂无 controller 调用方（控制层未迁移，属本任务范围外）。
