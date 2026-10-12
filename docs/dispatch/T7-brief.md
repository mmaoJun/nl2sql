# T7 派发说明 — 元数据服务与 Schema 压缩迁移

你是实现子代理，只依据本文件执行，不读其他计划文档。完成后写报告到 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T7-report.md`。

## 背景

新工程数据层已就绪（T3：`04c462f`/`502c1f3`/`ecb70db`，含适配器体系、DataSource 持久化、RedisConfig、commons-pool2）。本任务迁移元数据纵切面（对应课题"元数据服务（获取+压缩+展示）"中的**获取与压缩**）：

来源（**只读**）`F:\FANG_ZHENG\nlp2sql\backend`：
- `service/MetadataService.java`（130 行）：`getSchema` / `getTables` / `getColumns` / `refreshCache`，经 `DataSourceFactory` 取适配器 + Redis 缓存
- `service/SchemaCompressor.java`（185 行）：`compress(List<TableInfo>, String userQuery)`，TOKEN_BUDGET 800

## 交付物

1. `com.nl2sql.service.MetadataService` 与 `com.nl2sql.service.SchemaCompressor` 迁移完成，公开 API 与行为一致（仅包名替换；依赖 T3 已迁的 TableInfo/ColumnInfo/DataSourceFactory/RedisConfig）。
2. **缓存健壮性（与旧实现的有意差异，必须做）**：Redis 不可用时，缓存读、写、`refreshCache` 全路径优雅降级（记 WARN 日志，不抛异常）——这是后续 24h 稳定性与无 Redis 演示环境的要求。报告中明确列出此差异。
3. 新增集成测试 `MetadataServiceIntegrationTest`（沿用 T3 集成测试的连本机 MySQL 风格）：
   - 定位 `nlp2sql_test` 数据源：按 `database_name='nlp2sql_test'` 查 `datasources`（**不硬编码 id**；查不到则断言失败并给出清晰提示）
   - `getTables` 非空且数量合理（>=10；真实数量写进报告）
   - `getColumns(某表)` 非空
   - `compress(getTables(...), 样例问题)` 输出非空；报告中贴前 ~500 字符样例
   - `refreshCache` 在 Redis 不可用时仍正常返回；Redis 若在运行则补缓存命中路径（测试自动判定两种环境，均通过）
4. 如与 Redis 交互的旧写法确实需要容错改动（如 `RedisConfig` 序列化器注册顺序），做最小改动并说明；**不改 T3 其他文件**。

## 硬约束

- 不迁 NL2SQLEngine / PromptBuilder / ConversationService 等（后续任务）；不建空包、空文件。
- git：只 `git add backend/` 下本任务路径；**禁止 `git add -A` / `git add .`**；中文 conventional commit（建议实现 / 测试 两个提交）。
- 工作树中非本任务文件不要动。

## 验证（实际执行，真实输出进报告）

1. `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package` 通过；贴 surefire 的 `Tests run` 行（应为已有 17 例 + 新增）。
2. 报告记录：nlp2sql_test 表数量、压缩输出样例、Redis 状态探测结果（`netstat -ano | grep 6379`）与测试实际走的路径（降级/命中）。

## 报告要求（写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T7-report.md`）

- 文件清单；验证命令真实输出关键行
- **缓存降级差异说明**（改了哪里、为什么）
- 偏差与自主决策；提交 hash；遗留问题（供 T4 工具层参考）
