# T1 派发说明 — backend 骨架与依赖

你是实现子代理，只依据本文件执行，不读其他计划文档。完成后写报告到 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T1-report.md`。

## 背景

新仓库 `F:\FANG_ZHENG\nl2sql`（当前仅有 README），目标系统：基于 ≤7B 大模型的多数据源 NL2SQL 只读查询系统。架构已定稿（Spring Boot 3.3.4 + AgentScope Java 2.0.2 + MyBatis-Plus）。**本任务只搭"可编译、可启动、带统一响应契约"的骨架**；业务逻辑（安全校验、数据源适配、元数据、Agent 工具）是后续 T2–T5，不在本任务范围。

参考实现：`F:\FANG_ZHENG\nlp2sql\backend`（旧 Spring AI 版）——**只允许参考 pom / application.yml / 通用类写法，不得迁入任何业务代码，不得引入 Spring AI 依赖**。

## 交付物

1. `backend/` Maven 工程：groupId `com.nl2sql`，artifactId `nl2sql-backend`，Java 17，`spring-boot-starter-parent` 3.3.4。
2. pom 依赖（Lombok / JSqlParser 等写法照抄旧项目 pom）：
   - spring-boot-starter-web / validation / data-redis / test
   - mybatis-plus-spring-boot3-starter 3.5.7
   - mysql-connector-j、postgresql（版本由 parent 管，直接写坐标）
   - com.github.jsqlparser:jsqlparser:4.9
   - io.jsonwebtoken:jjwt-api / jjwt-impl / jjwt-jackson 0.12.6
   - org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0
   - lombok 1.18.36
   - io.agentscope:agentscope-core、agentscope-extensions-model-dashscope、agentscope-extensions-model-openai，版本统一 `<agentscope.version>2.0.2</agentscope.version>`
   - **禁止**：spring-boot-starter-security、任何 spring-ai-* 依赖
3. 通用契约类（与旧项目对应文件字段、静态工厂、行为完全对齐；包名 `com.nl2sql` 不变）：
   - `model/dto/ApiResponse.java` ← 旧 `F:\FANG_ZHENG\nlp2sql\backend\src\main\java\com\nlp2sql\model\dto\ApiResponse.java`
   - `common/ErrorCode.java`、`common/BusinessException.java` ← 旧同名文件
   - `config/GlobalExceptionHandler.java` ← 旧同名文件；如引用了 Spring Security 相关异常分支，删除该分支（本项目暂不引入 security）
4. 启动类 `com.nl2sql.Nl2SqlApplication`；`GET /api/v1/health` 返回 `ApiResponse.ok("ok")`（工厂方法名对齐旧 ApiResponse）。
5. `backend/src/main/resources/application.yml`（以旧项目 yml 为基线，差异点如下）：
   - `spring.application.name: nl2sql-backend`
   - 删除 `spring.ai.*` 全部段
   - server / datasource（nlp2sql_meta，root/050407）/ redis（050407）/ jackson / mybatis-plus / jwt / springdoc / logging 照抄旧项目
   - 配置前缀 `nlp2sql:` 改名 `nl2sql:`，其下保留 `sql-security`（max-limit 100、query-timeout-seconds 10、max-retry 2）与 `schema-cache.ttl-seconds: 3600`（模型候选配置留到 T5）
   - mybatis-plus 的 `type-aliases-package` 改为 `com.nl2sql.model`
6. 根目录 `.gitignore`：`target/`、`.idea/`、`*.iml`、`node_modules/`、`dist/`、`*.log`。

## 硬约束

- 不建空包、空文件；不迁移 security/、adapter/、service/、mapper/、prompt/ 等业务代码。
- git 操作：只 `git add backend/ .gitignore`；**禁止 `git add -A` / `git add .`**；不提交 `docs/dispatch/` 下文件；提交信息用中文 conventional commit（如 `feat(backend): Spring Boot + AgentScope 骨架与统一响应契约`），1–2 个提交按关注点拆分。
- 工作树中如有非本任务的未提交文件，不要动，报告里提一句即可。

## 验证（必须实际执行，真实输出写进报告）

1. `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package` 通过。
2. 后台启动（`mvn spring-boot:run` 或 `java -jar target/nl2sql-backend-*.jar`），`curl -s http://localhost:8080/api/v1/health` 返回 200 + ApiResponse JSON；验证后停止进程。
3. 若启动失败：确认本机 MySQL 在运行（root/050407）；仍失败则记录真实报错并在报告说明。

## 报告要求（写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T1-report.md`）

- 创建 / 修改文件清单
- 两条验证命令的真实输出关键行
- 与上述要求的偏差及自主决策
- 提交 hash 与提交信息
- 遗留问题（供 T2–T6 参考）
