# T3 派发说明 — 数据源适配体系与持久化迁移

你是实现子代理，只依据本文件执行，不读其他计划文档。完成后写报告到 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T3-report.md`。

## 背景

新工程 `F:\FANG_ZHENG\nl2sql\backend` 骨架已就绪（T1：`0358128`/`5ccb325`；T2：`10beac3`/`b97e2df` 已提供 SQL 安全校验）。本任务迁移"数据源"纵切面：适配器体系 + datasources 持久化 + 连接测试 + 基础配置，它是后续 T4（@Tool 工具）与 T7（元数据服务）的地基。

来源（**只读参考，不改旧仓库**）：`F:\FANG_ZHENG\nlp2sql\backend`，包名 `com.nlp2sql` → `com.nl2sql` 迁移：

| 旧路径 | 说明 |
| --- | --- |
| `adapter/DataSourceAdapter.java`、`DataSourceAdapterFactory.java`、`DataSourceFactory.java`、`DataSourceTypeInfo.java` | 适配器接口 + 工厂（约 289 行） |
| `adapter/jdbc/JdbcDataSourceAdapter.java`、`jdbc/{MySQLAdapter,MySQLAdapterFactory,PostgreSQLAdapter,PostgreSQLAdapterFactory}.java` | JDBC 基类 + 两个适配器（约 527 行） |
| `model/DataSource.java`、`TableInfo.java`、`ColumnInfo.java` | 实体与元数据类型 |
| `model/dto/DataSourceDTO.java`、`dto/QueryResult.java`、`model/vo/DataSourceVO.java` | DTO/VO |
| `mapper/DataSourceMapper.java` | MyBatis-Plus Mapper |
| `service/DataSourceService.java` | 数据源 CRUD 与连接测试（156 行） |
| `config/RedisConfig.java`、`config/MyBatisPlusConfig.java` | 基础配置（91 行） |

## 交付物

1. 上述类迁移到 `com.nl2sql` 对应包，公开 API 与行为保持一致，逻辑改动最小化。
2. pom 增补 `org.apache.commons:commons-pool2`（照抄旧 pom 写法，版本由 Boot parent 管理）。
3. 新仓库 `sql/` 目录：复制旧仓库 `sql/` 下全部脚本（`init_backend.sql` + `sample_data/*.sql`，含 25 表测试数据生成脚本）——**逐字复制，不改内容**。
4. 最小解耦：如旧 `DataSourceService` / 工厂耦合了用户、审计、安全组件（AuditService、AuditContext、JWT 等），去掉这些调用（保留等价公开方法签名），需要续接处留一行 `// TODO(W4 安全任务): 恢复审计/归属校验`。**不迁移** AuditService、SecurityConfig、WebConfig、Jwt* 等。
5. 新增集成测试（沿用旧项目"直连本机 MySQL"的风格，见旧 `ChatSessionMapperTest` 写法）：从 `nlp2sql_meta.datasources` 查询一条 mysql 类型记录 → `DataSourceFactory` 取适配器 → 对 `nlp2sql_sample` 或 `nlp2sql_test` 执行 获取表清单 / 获取列 / 取样 或 只读查询，断言非空；另覆盖 `testConnection`。**不要硬编码 datasource id**（按类型取首条）。旧测试目录中无适配器/持久化对应用例，无需迁移。
6. 测试对真实库有依赖是已知情形（单人本机开发，MySQL root/050407 常驻运行）。

## 硬约束

- 不改 T1/T2 已建契约类；不建空包、空文件；不扩大范围到元数据服务（T7）与工具层（T4）。
- git：只 `git add backend/ sql/` 下本任务路径；**禁止 `git add -A` / `git add .`**；中文 conventional commit，按关注点拆 2–3 个提交（例如 adapter 迁移 / 持久化与配置 / 测试）。
- 工作树中非本任务文件（`docs/`、`实习生课题(1).txt`、其他未提交草稿）不要动。

## 验证（实际执行，真实输出进报告）

1. `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package` 通过（含新集成测试；本机 MySQL 需运行，root/050407）。
2. 贴 surefire 的 `Tests run` 行。
3. `ls F:\FANG_ZHENG\nl2sql\sql` 列出复制后的文件清单。

## 报告要求（写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T3-report.md`）

- 文件清单（新增/修改）
- 验证命令真实输出关键行
- 解耦项与 TODO 清单（逐条列）
- 与要求的偏差及自主决策；提交 hash；遗留问题（供 T4/T7 参考）
