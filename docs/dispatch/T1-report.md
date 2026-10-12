# T1 实施报告 — backend 骨架与依赖

状态：**完成**（构建通过 + 服务实际启动 + 健康接口 200 验证通过）
执行日期：2026-10-12

## 1. 创建 / 修改文件清单

全部为新建，无对旧仓库（`F:\FANG_ZHENG\nlp2sql`）的任何写入，旧项目仅作只读参考。

| 文件 | 说明 |
| --- | --- |
| `F:\FANG_ZHENG\nl2sql\.gitignore` | `target/`、`.idea/`、`*.iml`、`node_modules/`、`dist/`、`*.log` |
| `F:\FANG_ZHENG\nl2sql\backend\pom.xml` | groupId `com.nl2sql` / artifactId `nl2sql-backend` / 1.0.0-SNAPSHOT，Java 17，`spring-boot-starter-parent` 3.3.4 |
| `...\backend\src\main\java\com\nl2sql\Nl2SqlApplication.java` | 启动类（含 `@EnableAsync`，对齐旧项目写法） |
| `...\common\ErrorCode.java` | 业务错误码常量，与旧项目逐字段一致 |
| `...\common\BusinessException.java` | 统一业务异常，与旧项目一致 |
| `...\config\GlobalExceptionHandler.java` | 全局异常处理（4 个分支：BusinessException / MethodArgumentNotValid / IllegalArgumentException / Exception） |
| `...\controller\HealthController.java` | `GET /api/v1/health` → `ApiResponse.ok("ok")` |
| `...\model\dto\ApiResponse.java` | 统一响应体 `{code,message,data}` + `ok/ok(data)/error(code,msg)/error(msg)` 静态工厂 |
| `...\resources\application.yml` | 以旧项目 yml 为基线，见第 4 节差异说明 |

依赖落点（`mvn dependency:list` 实测）：`io.agentscope:agentscope-core:2.0.2`、`agentscope-extensions-model-dashscope:2.0.2`、`agentscope-extensions-model-openai:2.0.2` 均解析成功；`spring-ai*` 与 `*security*` 依赖匹配数为 0（已确认无违规引入）。

## 2. 验证结果（实际执行）

### 2.1 `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package`

真实输出（`-q` 无 INFO 输出，以下为退出码与产物）：

```
BUILD_EXIT=0 elapsed=8s
-rw-r--r-- 1 lsz25 197609 76271986 Oct 12 09:41 target/nl2sql-backend-1.0.0-SNAPSHOT.jar
```

产物 MANIFEST 关键行：`Start-Class: com.nl2sql.Nl2SqlApplication`、`Spring-Boot-Version: 3.3.4`。

### 2.2 启动 + 健康接口（`java -jar target/nl2sql-backend-1.0.0-SNAPSHOT.jar`）

```
HEALTH up after 3s
HEALTH_HTTP=200 BODY={"code":200,"message":"success","data":"ok"}
2026-10-12 09:41:24 [main] [] INFO  o.s.b.w.e.tomcat.TomcatWebServer - Tomcat started on port 8080 (http) with context path '/'
2026-10-12 09:41:24 [main] [] INFO  com.nl2sql.Nl2SqlApplication - Started Nl2SqlApplication in 6.164 seconds (process running for 6.902)
STOPPED pid=12600
8080 free
```

响应头：`HTTP/1.1 200`、`Content-Type: application/json`。验证后进程已停止，8080 端口已释放。

启动日志中唯一 WARN（预期内，非阻塞）：

```
WARN o.m.s.mapper.ClassPathMapperScanner - No MyBatis mapper was found in '[com.nl2sql]' package. Please check your configuration.
```

环境事实：本机 MySQL 3306 已监听（探活 OPEN），Redis 6379 未运行 —— 因 Redis/Lettuce 为懒连接、T1 无 Redis 访问，启动与健康检查均不受影响。

### 2.3 契约类与旧项目一致性核对

`diff --strip-trailing-cr -I 'com\.nl'` 逐文件比对 `ApiResponse / ErrorCode / BusinessException / GlobalExceptionHandler`，四个文件均为 **ALIGNED（仅包名差异）**，字段、静态工厂、异常分支、日志文案完全一致。

## 3. 提交

| hash | 提交信息 |
| --- | --- |
| `0358128` | `build(backend): 搭建 Maven 骨架、AgentScope 依赖与运行配置` |
| `5ccb325` | `feat(backend): 统一响应契约、全局异常处理与健康检查接口` |

- 仅暂存 `backend/` 与 `.gitignore`（`git add backend/ .gitignore`，未使用 `git add -A` / `git add .`），`git status` 中 `target/` 未进入版本库。
- 工作树存在非本任务文件，未做任何处理：未跟踪的 `docs/`（含本 dispatch 目录，按要求不提交）与 `实习生课题(1).txt`。

## 4. 偏差与自主决策

1. **新增 `controller/HealthController.java`**：brief 要求 `GET /api/v1/health` 返回 `ApiResponse.ok("ok")`，故新建该类承载路由（`@RestController @RequestMapping("/api/v1")`），未采用"把接口写在启动类上"的做法。这是本任务唯一新增的包（`controller`），非空包。
2. **yml 中 `logging.level.org.springframework.security: INFO` 未保留**：项目不引入 security，该行属死配置；其余 logging 段（`com.nl2sql: DEBUG` + console pattern）照抄。若需严格逐字对齐可后补。
3. **yml 的 `jwt.secret` 值逐字照抄旧项目**（`nlp2sql-jwt-secret-key-2024-...`，仅字符串内容，非配置键）；如希望改名以体现新项目，可在后续任务调整。
4. **未引入 `commons-pool2`**：brief 的依赖清单未列出该项（旧 pom 有）。实测未引入时 `spring.data.redis.lettuce.pool.*` 配置不生效但**不影响启动**（无报错无警告）；若 T5 需要 Redis 连接池，需补该依赖。
5. **未显式声明 `jackson-databind`**：由 `spring-boot-starter-web` 传递引入，brief 清单同样未列出。
6. **行尾/编码**：新文件为 UTF-8 + LF（旧项目为 CRLF）；git 提示下次 touch 时转 CRLF，不影响编译。
7. **未建 `src/test/java`**：本任务无测试交付物，按"不建空包"约束未创建空测试目录；`spring-boot-starter-test` 依赖已按 brief 引入（当前无测试用例，`mvn test` 阶段为空）。
8. **yml 配置前缀调整**：`nlp2sql:` → `nl2sql:`，其下 `sql-security`（max-limit 100 / query-timeout-seconds 10 / max-retry 2）与 `schema-cache.ttl-seconds: 3600` 保留；旧 yml 顶层的 `prompt.version`、`nlp2sql.model.*`、`nlp2sql.memory.*`、`spring.ai.*` 均按要求未迁入（`nlp2sql.model.*` 相关 bean 留待 T5）。
9. 构建使用本机 JDK 21 编译 `--release 17`（本机仅装 JDK 21），`Build-Jdk-Spec: 21`；字节码目标为 17，符合 brief 的 Java 17 要求。

## 5. 遗留问题（供 T2–T6 参考）

1. **无 Mapper 扫描目标**：启动 WARN "No MyBatis mapper was found in '[com.nl2sql]'" 是预期现象，T3 落地 `mapper/` 后消失；`mybatis-plus.mapper-locations: classpath:mapper/*.xml` 与 `type-aliases-package: com.nl2sql.model` 已就绪。
2. **Redis 未启动**：本机 6379 无监听，且未引入 `commons-pool2`（见偏差 4）。T2–T5 若使用 Redis（会话/缓存）需先启动 Redis 并补依赖。
3. **元数据库沿用 `nlp2sql_meta`**：yml 中 datasource 指向旧库名（按 brief 要求）；T3 迁移实体表结构时需确认库内表是否已存在/是否需要新迁移脚本。
4. **安全/AI 依赖缺失是设计如此**：无 `spring-boot-starter-security`、无 `spring-ai-*`；T2 的 SQL 安全校验只能基于 JSqlParser 4.9 自行实现，T5 的模型调用走 AgentScope 2.0.2 的 dashscope/openai 扩展（`DASHSCOPE_API_KEY` 等候选模型配置留待 T5）。
5. **契约扩展点**：`ErrorCode` 未包含 T2–T5 可能新增的错误码（如工具调用相关），如需扩展请沿用 1xxx/2xxx/3xxx 分段注释维护。
6. **springdoc 已就绪**：`/api-docs` 与 `/swagger-ui.html` 路径已配置，后续新增 Controller 会自动收录。
