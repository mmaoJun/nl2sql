# T10 派发说明 — 认证与审计迁移（JWT + 审计日志）

你是实现子代理，只依据本文件执行，不读其他计划文档。完成后写报告到 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T10-report.md`。

## 背景

T9 聊天通道已就位（`POST /api/v1/chat`、`GET /api/v1/chat/stream`，契约见 `docs/dispatch/T9-report.md`）。本任务把旧项目的 **JWT 认证与审计日志** 迁移过来：聊天/数据源/元数据等接口从本轮起受保护，聊天查询落审计记录（课题安全要求："操作审计日志"）。

旧项目事实（已核实，**只读参考** `F:\FANG_ZHENG\nlp2sql\backend`）：
- `config/SecurityConfig.java` 规则：`/api/v1/auth/**` 与 swagger/docs 放行，**其余 anyRequest().authenticated()**；`csrf disable`；BCryptPasswordEncoder。
- `security/JwtAuthenticationFilter.java`、`security/JwtTokenProvider.java`（jjwt 0.12.6，密钥读 `jwt.secret` / `jwt.expiration`，新 yml 已有该配置）。
- `service/AuthService.java`：register（用户名唯一 + BCrypt + 默认角色 USER）/ login（签发 JWT）/ resolveUserId。`controller/AuthController.java`、`model/dto/LoginRequest|LoginResponse`、`model/User.java`、`mapper/UserMapper.java`。
- 审计：`service/AuditService.java`（构造注入 `AuditLogMapper`）、`service/AuditContext.java`（ThreadLocal）、`model/AuditLog.java`、`mapper/AuditLogMapper.java`、`common/TraceIdFilter.java`（MDC traceId，新 yml 日志 pattern 已引用 `%X{traceId}`）。**旧项目审计只挂在聊天链路**（NL2SQLEngine 调用），数据源 CRUD 未审计。
- 相关表（`nlp2sql_meta` 已存在）：`users`、`audit_logs`。

## 交付物

1. **pom 增补** `spring-boot-starter-security`（版本由 parent 管理）。
2. 上述认证类全量迁移到 `com.nl2sql`（包名对应：security/config/service/controller/model/mapper/common）。行为对齐旧实现；如 `SecurityConfig`/过滤链依赖 `WebConfig`（如 CORS）则最小移植相应部分，否则不迁。
3. **放行规则**（与旧一致 + 一项新增）：`/api/v1/auth/**`、`/api/v1/health`（部署探活）、docs 路径放行；**其余全部需认证**（含 chat / datasources / metadata）。
4. **审计接入**：`ChatService` 每次问答落一条审计（对齐旧 `AuditService.log(...)` 的参数语义：userId（由 SecurityContext 用户名经 `resolveUserId` 解析）、datasourceId、自然语言输入、生成 SQL、成功/失败与失败原因等——具体参数以旧实现为准）；`AuditContext`/`TraceIdFilter` 按旧行为接入。
5. **修复 T9 遗留**：`POST /api/v1/chat` 缺 body / 非法 JSON 时返回 `4001` 而不是 500。
6. 测试：
   - `AuthIntegrationTest`：注册（随机用户名避免污染）→ 登录拿 token → 带 token 访问受保护接口 200；错密码拒绝；无 token 访问受保护接口被拒（断言实际状态码，与旧行为一致）。
   - **更新 `ChatControllerIntegrationTest`**（T9 留下）：请求带登录 token（测试内注册用户并登录）；同步 1 例 + SSE 1 例保持通过。
   - 审计验证：在一次聊天调用后断言 `audit_logs` 新增对应记录（与聊天用例合并，避免额外 LLM 调用）。
   - **真实 LLM 预算硬约束：新增/修改合计最多 2 次完整问答**；模型行为问题最多修 1 次，不无限调参。
7. 不改 T2-T9 其他既有行为；必要的最小修改（含 T9 测试）在报告说明。

## 硬约束

- 不做会话归属（T11 做）；不实现前端。
- git：只 `git add backend/` 本任务路径；**禁止 `git add -A` / `git add .`**；中文 conventional commit，2–3 个提交（认证迁移 / 审计接入与放行规则 / 测试与修复）。
- 工作树中非本任务文件不要动。

## 验证（实际执行，真实输出进报告）

1. `cd F:\FANG_ZHENG\nl2sql\backend && mvn -q clean package`：39 例 + 新增全绿（贴 surefire 行）。
2. 报告贴：注册/登录/无 token 拒绝/带 token 聊天 的真实响应摘要（**不得贴任何完整 token，截断为前 12 字符**）；审计记录落库证据（id/字段摘要）。

## 报告要求（写入 `F:\FANG_ZHENG\nl2sql\docs\dispatch\T10-report.md`）

- 文件清单；放行规则最终表；审计参数语义
- 验证命令真实输出关键行（token 一律截断）
- 与旧实现的偏差（如放行范围增补 health）及理由
- 提交 hash；遗留问题（供 T11-T12）
