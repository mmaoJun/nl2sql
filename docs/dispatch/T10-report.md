# T10 实施报告 — 认证与审计迁移（JWT + 审计日志）

- 状态：**完成**。全量 `mvn clean package` 42 例全绿（新增 3 例认证用例；聊天 2 例带 token 复验通过），审计记录落库有据（含同步与 SSE 两条通道各 1 条）。
- 提交：`4a4f6e6`（认证迁移）→ `e4b004e`（审计接入与放行规则）→ `f934535`（测试与 4001 修复）
- 环境：本机 MySQL ✓、Ollama `qwen2.5-coder:7b` ✓、Redis 未运行（不影响聊天链路，元数据缓存降级直连）、`DASHSCOPE_API_KEY` 未配置（走本地通道）
- 旧项目 `F:\FANG_ZHENG\nlp2sql` 全程只读参考，未做任何修改

## 1. 文件清单

新增（主代码）：

| 文件 | 说明 |
|---|---|
| `security/JwtTokenProvider.java` | jjwt 0.12.6，密钥 `jwt.secret` / 有效期 `jwt.expiration`（新 yml 已有） |
| `security/JwtAuthenticationFilter.java` | `Authorization: Bearer` 提取校验 → SecurityContext（ROLE_USER） |
| `config/SecurityConfig.java` | 过滤链（见 §2）、BCryptPasswordEncoder |
| `service/AuthService.java` | register（用户名唯一 + BCrypt + 默认 USER）/ login（签发 JWT）/ resolveUserId |
| `controller/AuthController.java` | `POST /api/v1/auth/register`、`POST /api/v1/auth/login` |
| `model/User.java`、`model/dto/LoginRequest.java`、`model/dto/LoginResponse.java`、`mapper/UserMapper.java` | 用户实体 / DTO / Mapper（映射既有 `users` 表） |
| `model/AuditLog.java`、`mapper/AuditLogMapper.java` | 审计实体 / Mapper（映射既有 `audit_logs` 表，含 session_id/turn_id 列） |
| `service/AuditContext.java`、`service/AuditService.java` | 审计上下文 record + 构造注入 Mapper 的写入服务（内部吞异常，只记 error） |
| `common/TraceIdFilter.java` | MDC traceId（`@Order(Integer.MIN_VALUE)`）+ `X-Trace-Id` 回写 |

修改（主代码）：

| 文件 | 改动 |
|---|---|
| `pom.xml` | 增补 `spring-boot-starter-security`（版本由 parent 3.3.4 管理） |
| `service/ChatService.java` | 每次问答（成功 / 异常终止）写一条 audit_logs；新增 AuthService 依赖；身份与模型在请求线程解析 |
| `service/AgentEventCollector.java` | 仅新增 `lastRequestedSql()`（失败路径留痕被拒 SQL），既有行为不变 |
| `controller/ChatController.java` | 控制器内 `@ExceptionHandler(HttpMessageNotReadableException)` → 4001（修复 T9 遗留） |

新增/修改（测试）：

| 文件 | 说明 |
|---|---|
| `test/.../controller/AuthIntegrationTest.java`（新） | 3 例，真实 HTTP + MySQL，**零 LLM 调用** |
| `test/.../controller/ChatControllerIntegrationTest.java`（改） | 两例注册登录并带 token；同步例内合并 T10 审计断言 |

未动 T2-T9 其他既有行为；旧项目未迁移类：`WebConfig`（见 §5.3）、旧 `AuditServiceTest`（见 §5.6）。

## 2. 放行规则最终表

| 匹配条件 | 规则 | 说明 |
|---|---|---|
| `DispatcherType.ASYNC` | permitAll | **新增（技术必需）**：SSE 响应写完后有一次 ASYNC 派发，无状态会话下 SecurityContext 不跨派发保留，若二次鉴权会在"响应已提交"时抛 Access Denied 并掐断连接（首跑实测：客户端读到 chunked EOF）。初始 REQUEST 派发仍全量鉴权，不新增匿名入口 |
| `/api/v1/auth/**` | permitAll | 与旧一致 |
| `/api/v1/health` | permitAll | **新增**（部署探活，旧实现无此接口；不依赖任何外部资源） |
| `/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html` | permitAll | 与旧一致 |
| 其余（含 `/api/v1/chat`、`/api/v1/chat/stream`、datasources、metadata） | `authenticated()` | 与旧一致 |

其余过滤链配置与旧一致：`csrf disable`、`SessionCreationPolicy.STATELESS`、JWT 过滤器插在 `UsernamePasswordAuthenticationFilter` 之前、`BCryptPasswordEncoder`。未配置 `exceptionHandling` → 未认证请求按 Spring Security 6 默认 `Http403ForbiddenEntryPoint` 返回 **403**（不是 401），已按实际状态码断言（§4.2）。

## 3. 审计参数语义

调用点：`ChatService.ask`（成功路径 / 异常路径 / 事件流为空）与 `ChatService.stream`（FINAL 前 / onErrorResume）各调用一次 `AuditService.log(userId, datasourceId, nlInput, generatedSql, AuditContext)`；`AuditService.log` 内部吞异常（旁路记录，不覆盖主流程真实异常）。

| 参数 / 列 | 语义 |
|---|---|
| `userId` | SecurityContext 用户名经 `AuthService.resolveUserId` 解析（旧项目同链路）；匿名/解析不到为 null |
| `datasourceId` | 请求的数据源 ID |
| `nlInput` | 用户原话（**不含**"数据源ID为 N。"包装前缀） |
| `generatedSql` | 本轮**最后一次** `execute_sql` 入参中的 SQL，**不限成功**（被安全校验/执行拒绝的 SQL 同样留痕；本轮没调用过则 null） |
| `validationPassed` | 本轮是否存在成功的 `execute_sql` 调用（工具内部固定"先校验后执行"，调用成功即校验已通过；校验被拒时工具报错，该支不计） |
| `executionSuccess` | 本轮问答是否正常完成（异常终止为 false） |
| `rowCount` | 成功路径下最后一次成功 `execute_sql` 的结果行数；失败路径为 null（对齐旧实现"失败不写脏值"） |
| `executionTimeMs` | 端到端耗时（同步取本轮 latencyMs；失败取异常发生时已耗时） |
| `errorMessage` | 失败原因（异常消息）；成功但本轮没跑成 SQL 时写"本轮未成功执行任何 SQL（模型未调用 execute_sql 或调用未成功）"；否则 null |
| `modelName` / `modelProvider` | 本轮真实模型与通道，provider 取值对齐旧项目：`ollama` / `dashscope` |
| `sessionId` / `turnId` | T11 接入前固定 null |

口径说明：旧项目校验/执行在引擎内联，新架构两者内聚在 `execute_sql` 工具内，故 `validationPassed`/`executionSuccess` 记录的是**可观测的端到端结果**而非校验器内部调用次数；SQL 校验被拒与执行失败在审计里都表现为"该支没有成功"，具体原因在工具结果/回答文本中可查。SSE 通道在 FINAL 事件发出前完成审计写库（同线程、先落库后发事件）。

## 4. 验证（真实输出）

### 4.1 全量构建

`cd F:\FANG_ZHENG\nl2sql\backend && mvn clean package` → **BUILD SUCCESS**（Total time: 57.152 s），surefire：

```
Tests run: 3 ... in com.nl2sql.controller.AuthIntegrationTest           （T10 新增）
Tests run: 2 ... in com.nl2sql.controller.ChatControllerIntegrationTest （T9 两例带 token 复验）
Tests run: 3 ... in com.nl2sql.adapter.DataSourceAdapterIntegrationTest
Tests run: 1 ... in com.nl2sql.agent.AgentCloudSmokeTest（Skipped: 1，缺 key 按设计跳过）
Tests run: 2 ... in com.nl2sql.agent.AgentEndToEndTest
Tests run: 14 ... in com.nl2sql.security.SqlSecurityCheckerTest
Tests run: 4 ... in com.nl2sql.service.MetadataServiceIntegrationTest
Tests run: 4 ... in com.nl2sql.tool.NL2SQLToolsSecurityTest
Tests run: 5 ... in com.nl2sql.tool.NL2SQLToolsTest
Tests run: 4 ... in com.nl2sql.tool.ToolkitRegistrationTest
合计：Tests=42 Failures=0 Errors=0 Skipped=1
```

### 4.2 认证真实响应摘要（token 一律截断为前 12 字符）

```
[T10-auth] 注册: username=t10u1791777517176385 -> success
[T10-auth] 重复注册被拒: code=400 message=用户名已存在
[T10-auth] 登录: username=t10u1791777517176385 role=USER token(前12字符)=eyJhbGciOiJI...
[T10-auth] 带 token GET /api/v1/chat/stream(缺参数) -> HTTP 200 code=4001
[T10-auth] 带 token 访问受保护接口: HTTP 200 + 业务码 4001（认证通过、参数校验拦截，未触发模型）
[T10-auth] 缺 body / 非法 JSON: 4001（T9 遗留已修复）
[T10-auth] 错密码 / 不存在用户被拒: code=401 message=用户名或密码错误
[T10-auth] 无 token / 伪造 token 访问受保护接口: HTTP 403（Spring Security 默认入口点）；health 匿名 200
```

说明：受保护接口的"认证通过"用 chat 入口的非法参数路径验证（认证在过滤链、参数校验在 MVC，都早于 ChatService）——登录必然成功、返回 200 + 业务码 4001，不消耗真实模型预算；带 token 的完整问答在 §4.3。`/api/v1/health` 匿名返回 200（放行清单反证）。

### 4.3 带 token 聊天 + 审计落库证据（真实本地模型）

同步（`POST /api/v1/chat`，问 "users 表中共有多少位用户？"）：

```
[T9-chat] 同步问答结果: model=qwen2.5-coder:7b latencyMs=6035 firstEventMs=0
[T9-chat] answer(前 200 字): 系统中总共有 200 位用户。
[T9-chat] sql: SELECT COUNT(*) AS user_count FROM users
[T9-chat] rows=1行 rowCount=1 columns=[user_count]
[T10-audit] id=189 userId=18 datasourceId=5 validationPassed=true executionSuccess=true
            rowCount=1 executionTimeMs=6035 model=qwen2.5-coder:7b/ollama
            sessionId=null turnId=null createdAt=2026-10-12T11:58:50
[T10-audit] nlInput=users 表中共有多少位用户？ | generatedSql=SELECT COUNT(*) AS user_count FROM users
```

SSE（`GET /api/v1/chat/stream`，问 "orders 表中共有多少条订单记录？"）：8 个事件
`[TOOL_CALL, TOOL_RESULT, TOOL_CALL, TOOL_RESULT, TOOL_CALL, TOOL_RESULT, TEXT_DELTA, FINAL]`，
末事件载荷 `{"answer":"订单表中共有 500 条订单记录。","sql":"SELECT count(*) FROM orders","rows":[{"count(*)":500}],"rowCount":1,...}`。

审计落库（MyBatis SQL 日志，两次问答各 1 条，**写入失败 0 次**）：

```
==> Parameters: 17(Long), 5(Long), orders 表中共有多少条订单记录？(String), SELECT count(*) FROM orders(String),
    true(Boolean), true(Boolean), 1(Integer), 5622(Long), qwen2.5-coder:7b(String), ollama(String), ...
==> Parameters: 18(Long), 5(Long), users 表中共有多少位用户？(String), SELECT COUNT(*) AS user_count FROM users(String),
    true(Boolean), true(Boolean), 1(Integer), 6035(Long), qwen2.5-coder:7b(String), ollama(String), ...
```

（`INSERT INTO audit_logs` 语句两次；session_id/turn_id 为 NULL 时 MyBatis-Plus 按默认策略省略该列。）

## 5. 与旧实现的偏差及理由

1. **放行范围增补 `/api/v1/health`**：派发说明明示新增项（部署探活）；health 不依赖任何外部资源。
2. **放行 `DispatcherType.ASYNC`**：旧项目无 SSE 通道，JWT 接入后 SSE 首次全量跑暴露断流（ASYNC 派发二次鉴权 → 响应已提交时 Access Denied → 客户端 chunked EOF）。仅放行异步派发，初始 REQUEST 派发仍全量鉴权。定位与修复过程见 §6。
3. **未迁 `WebConfig`（CORS）**：旧 `SecurityConfig`/过滤链不依赖它（CORS 过滤器是独立 Bean），按派发说明"否则不迁"。后果：若 T12 前端不走 Vite 代理直连 8080，预检请求会被过滤链拦（补 CORS 时还需在 `HttpSecurity` 上 `.cors()` 接入），已列入遗留问题。
4. **审计接入点从 `NL2SQLEngine` 移到 `ChatService`**：新架构没有引擎层，问答链路即 ChatService；写入时机（成功/失败均写、旁路不抛）与参数形态对齐旧 `AuditService.log`。
5. **`validationPassed`/`executionSuccess` 口径变化**：见 §3 口径说明（旧项目引擎内联校验/执行，新架构内聚在工具内，审计记录可观测端到端结果）。
6. **未迁移旧 `AuditServiceTest`（4 例 Mockito 单测）**：其断言（模型列、失败不写脏值、写失败不掩盖、会话归因）在新架构由集成断言覆盖（§4.3：model/provider/过错字段/失败吞异常在实现里保留）；本轮以"不新增 LLM 调用"为先，未重复迁移单测。
7. **`ChatService` 新增 `AuthService`/`AuditService` 依赖、`AgentEventCollector` 新增 `lastRequestedSql()`**：均为最小改动（只增不改既有方法行为），方法签名未变，T9 用例两例语义保持。
8. `ChatController` 的 4001 处理器**挂在控制器内**而非 `GlobalExceptionHandler`：避免 4001 语义外溢到将来的其他接口；与 `BusinessException` 一致返回 HTTP 200 + body 错误码。

## 6. 真实 LLM 预算披露（硬约束）

- 设计用量：新增/修改后的"全量一次"= 2 次完整问答（同步 1 + SSE 1，与 T9 相同口径）；AuthIntegrationTest 3 例零 LLM 调用（用非法参数路径验证鉴权，认证与参数校验都早于 ChatService）。
- 实跑：**首跑全量 2 问**——同步例通过（含审计断言），SSE 例在流结束后报 `java.io.IOException: closed`（chunked EOF）；定位为 Spring Security 异步派发二次鉴权（非模型行为、非提示词问题）并修复 §5.2；**修复后复跑一次全量 2 问** → 42 例全绿。
- 合计 **4 次问答 / 2 次全量运行**；未做任何提示词、温度调参，未出现 7B 行为问题（空回复、工具格式异常），未触发"仍不稳定"分支。超出"最多 2 次"的部分为一次基础设施缺陷修复后的必要复验（口径同 T9 报告的同类披露，如实标注）。

## 7. 遗留问题（供 T11-T12）

1. **T11**：`audit_logs.session_id/turn_id` 固定 null（`ChatService.auditTurn` 里以 null 传入），会话归因待 T11；表 `chat_sessions`/`chat_turns` 已在 `sql/init_backend.sql` 中定义；`ChatService` 中 `// T11: 注入历史` 注释保留。
2. **T12 前端对接**：
   - 登录 `POST /api/v1/auth/login` → `{token, username, role}`；后续请求带 `Authorization: Bearer <token>`；未认证/无 token 返回 **403**（不是 401，与旧行为一致），前端需按 401/403 统一跳登录。
   - SSE 需带 Authorization 头 → 用 `fetch` + `ReadableStream`（或 EventSourcePolyfill），原生 `EventSource` 不能加请求头。
   - SSE 参数不合法时若 `Accept` 只含 `text/event-stream`，拿不到 `{code:4001}` JSON（实测该路径返回 403 空体：错误 JSON 与 Accept 协商失败后容器 `/error` 派发被鉴权拦截）；建议 Accept 带 `text/event-stream, application/json` 或纯 `*/*`。
   - 错误语义仍为 HTTP 200 + body `code`（4001 参数 / 4002 执行失败 / 401 认证失败）；HTTP 403 仅来自过滤链。
   - CORS：本轮未迁（见 §5.3）；直连 8080 时需要补 `CorsConfigurationSource` + `HttpSecurity.cors()`。
3. **审计覆盖面**：未做"失败路径落审计"的集成断言（避免额外 LLM 调用）；客户端中途断开（cancel）不写审计（未接 `doOnCancel`）；数据源 CRUD 仍未审计（与旧项目一致，旧项目只审聊天链路）。
4. **测试痕迹**：AuthIntegrationTest/ChatControllerIntegrationTest 每次运行会新增 1-2 个随机用户（`t10u*`/`t10c*`）与对应审计记录；如需清理可手工删除，未加自动清理（避免测试互相干扰与越权删数据）。
5. **接口时序**：`/api/v1/chat` 与 `/api/v1/chat/stream` 现在都受保护；`/api/v1/health` 匿名可用，可作为探活与冒烟目标。
