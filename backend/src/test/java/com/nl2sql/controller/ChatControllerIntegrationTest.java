package com.nl2sql.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.mapper.AuditLogMapper;
import com.nl2sql.mapper.DataSourceMapper;
import com.nl2sql.mapper.UserMapper;
import com.nl2sql.model.AuditLog;
import com.nl2sql.model.DataSource;
import com.nl2sql.model.User;
import com.nl2sql.model.dto.ApiResponse;
import com.nl2sql.model.dto.LoginResponse;
import com.nl2sql.model.vo.ChatResult;
import com.nl2sql.model.vo.ChatStreamEvent;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聊天 HTTP 通道集成验证（真实本地模型：MySQL + Ollama 均可达才执行）。
 *
 * <p>两例各一次完整问答（真实 LLM 预算上限即此两问）：
 * <ol>
 *   <li>同步 {@code POST /api/v1/chat}：断言 answer 非空、rows 非空、latencyMs &gt; 0；
 *       并验证 T10 审计——本轮问答在 {@code audit_logs} 落一条对应用户的记录；</li>
 *   <li>SSE {@code GET /api/v1/chat/stream}：收集事件流直到服务端关闭，
 *       断言出现 TOOL_CALL / TOOL_RESULT（且调用先于结果）并以 FINAL 收尾，
 *       FINAL 载荷（ChatResult）answer 非空、rows 非空。</li>
 * </ol>
 *
 * <p>T10 起两个入口均受 JWT 保护：测试内注册随机用户并登录，请求携带
 * {@code Authorization: Bearer <token>}（token 只在进程内传递，不落盘、不进报告）。
 *
 * <p>环境守卫模式与 {@code AgentE2ESupport.assumeLocalStackUp} 一致（端口探测；
 * 该支持类为测试包私有，本类按 T7 的做法就地实现）。数据源按库名
 * {@code nlp2sql_test} 定位，不硬编码 id。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatControllerIntegrationTest {

    private static final String TARGET_DATABASE = "nlp2sql_test";

    /** 测试内注册用户所用密码（仅测试进程内使用） */
    private static final String PASSWORD = "t10-chat-pass-123456";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private AuditLogMapper auditLogMapper;

    /** 同步接口：一次完整问答，校验 answer / rows / latencyMs，并断言审计落库。 */
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void syncChatReturnsAnswerWithRows() {
        assumeLocalStackUp();
        Long datasourceId = nlp2sqlTestDatasourceId();
        Session session = registerAndLogin();
        Long userId = userIdOf(session.username());

        String question = "users 表中共有多少位用户？";
        Map<String, Object> body = Map.of(
                "question", question,
                "datasourceId", datasourceId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(session.token());

        ResponseEntity<ApiResponse<ChatResult>> response = restTemplate.exchange(
                "/api/v1/chat", HttpMethod.POST, new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {
                });

        assertEquals(200, response.getStatusCode().value(), "HTTP 状态应为 200");
        ApiResponse<ChatResult> api = response.getBody();
        assertNotNull(api, "响应体不应为空");
        assertEquals(200, api.getCode(), "业务码应为 200，实际: " + api.getMessage());
        ChatResult result = api.getData();
        assertNotNull(result, "data 不应为空");

        assertNotNull(result.getAnswer(), "answer 不应为 null");
        assertFalse(result.getAnswer().isBlank(), "answer 不应为空");
        assertNotNull(result.getRows(), "rows 不应为 null");
        assertFalse(result.getRows().isEmpty(), "rows 不应为空（应命中一次成功的 execute_sql）");
        assertTrue(result.getRowCount() > 0, "rowCount 应大于 0，实际 " + result.getRowCount());
        assertTrue(result.getLatencyMs() > 0, "latencyMs 应大于 0，实际 " + result.getLatencyMs());
        assertTrue(result.getFirstEventMs() >= 0, "firstEventMs 不应为负");

        System.out.println("[T9-chat] 同步问答结果: model=" + result.getModel()
                + " latencyMs=" + result.getLatencyMs() + " firstEventMs=" + result.getFirstEventMs());
        System.out.println("[T9-chat] answer(前 200 字): " + truncate(result.getAnswer(), 200));
        System.out.println("[T9-chat] sql: " + result.getSql());
        System.out.println("[T9-chat] rows=" + result.getRows().size() + "行 rowCount=" + result.getRowCount()
                + " columns=" + result.getColumns());
        System.out.println("[T9-chat] trace=" + result.getTrace());

        // T10 审计：本轮问答应在 audit_logs 落一条属于该用户的记录（与聊天用例合并，不额外消耗模型预算）
        AuditLog audit = auditLogMapper.selectOne(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getUserId, userId)
                .orderByDesc(AuditLog::getId)
                .last("LIMIT 1"));
        assertNotNull(audit, "聊天后应为用户 " + userId + " 落一条审计记录");
        assertEquals(question, audit.getNlInput(), "审计 nlInput 应为用户原话");
        assertEquals(datasourceId, audit.getDatasourceId(), "审计 datasourceId 应为请求的数据源");
        assertNotNull(audit.getGeneratedSql(), "审计 generatedSql 不应为空");
        assertEquals(Boolean.TRUE, audit.getValidationPassed(), "审计 validationPassed 应为 true");
        assertEquals(Boolean.TRUE, audit.getExecutionSuccess(), "审计 executionSuccess 应为 true");
        assertNotNull(audit.getRowCount(), "审计 rowCount 不应为 null");
        assertTrue(audit.getRowCount() > 0, "审计 rowCount 应大于 0，实际 " + audit.getRowCount());
        assertNotNull(audit.getExecutionTimeMs(), "审计 executionTimeMs 不应为 null");
        assertTrue(audit.getExecutionTimeMs() > 0, "审计 executionTimeMs 应大于 0");
        assertNull(audit.getErrorMessage(), "成功路径审计 errorMessage 应为空");
        assertNotNull(audit.getModelName(), "审计 modelName 不应为空");
        assertEquals("ollama", audit.getModelProvider(), "本地通道审计 modelProvider 应为 ollama");
        assertNull(audit.getSessionId(), "sessionId 归因属 T11，本轮应为空");
        assertNull(audit.getTurnId(), "turnId 归因属 T11，本轮应为空");
        assertNotNull(audit.getCreatedAt(), "审计 createdAt 应由自动填充写入");

        System.out.println("[T10-audit] id=" + audit.getId()
                + " userId=" + audit.getUserId()
                + " datasourceId=" + audit.getDatasourceId()
                + " validationPassed=" + audit.getValidationPassed()
                + " executionSuccess=" + audit.getExecutionSuccess()
                + " rowCount=" + audit.getRowCount()
                + " executionTimeMs=" + audit.getExecutionTimeMs()
                + " model=" + audit.getModelName() + "/" + audit.getModelProvider()
                + " sessionId=" + audit.getSessionId() + " turnId=" + audit.getTurnId()
                + " createdAt=" + audit.getCreatedAt());
        System.out.println("[T10-audit] nlInput=" + audit.getNlInput()
                + " | generatedSql=" + audit.getGeneratedSql());
    }

    /** SSE 接口：收集事件流，校验工具事件序列与 FINAL 收尾。 */
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void sseChatStreamsToolEventsThenFinal() throws Exception {
        assumeLocalStackUp();
        Long datasourceId = nlp2sqlTestDatasourceId();
        Session session = registerAndLogin();

        String question = "orders 表中共有多少条订单记录？";
        URI uri = URI.create("http://127.0.0.1:" + port + "/api/v1/chat/stream?question="
                + URLEncoder.encode(question, StandardCharsets.UTF_8)
                + "&datasourceId=" + datasourceId);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .header("Authorization", "Bearer " + session.token())
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode(), "SSE 应返回 HTTP 200");
        assertTrue(String.valueOf(response.headers().firstValue("Content-Type").orElse(""))
                        .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE),
                "Content-Type 应为 text/event-stream，实际: "
                        + response.headers().firstValue("Content-Type").orElse("(缺失)"));

        // 读到服务端关闭（契约：正常结束即 close），保留全部事件的名称与载荷
        List<SseEvent> events = readAllEvents(response.body());

        assertFalse(events.isEmpty(), "SSE 事件流不应为空");
        List<String> names = events.stream().map(SseEvent::name).toList();
        assertTrue(names.contains(ChatStreamEvent.EVENT_TOOL_CALL),
                "应出现 TOOL_CALL 事件，实际序列: " + names);
        assertTrue(names.contains(ChatStreamEvent.EVENT_TOOL_RESULT),
                "应出现 TOOL_RESULT 事件，实际序列: " + names);
        assertTrue(names.indexOf(ChatStreamEvent.EVENT_TOOL_CALL)
                        < names.indexOf(ChatStreamEvent.EVENT_TOOL_RESULT),
                "TOOL_CALL 应先于 TOOL_RESULT，实际序列: " + names);

        SseEvent last = events.get(events.size() - 1);
        assertEquals(ChatStreamEvent.EVENT_FINAL, last.name(), "最后一个事件应为 FINAL，实际序列: " + names);
        assertEquals(1, names.stream().filter(ChatStreamEvent.EVENT_FINAL::equals).count(),
                "FINAL 应恰好出现一次");

        JsonNode finalData = MAPPER.readTree(last.data());
        assertFalse(finalData.path("answer").asText().isBlank(), "FINAL.answer 不应为空");
        assertTrue(finalData.path("rows").isArray() && !finalData.path("rows").isEmpty(),
                "FINAL.rows 不应为空");
        assertTrue(finalData.path("latencyMs").asLong() > 0, "FINAL.latencyMs 应大于 0");

        // 留证：前 10 个事件与关键载荷
        System.out.println("[T9-chat] SSE 事件序列(前 10 个): " + names.subList(0, Math.min(10, names.size())));
        System.out.println("[T9-chat] SSE 事件总数=" + events.size()
                + " TEXT_DELTA=" + names.stream().filter(ChatStreamEvent.EVENT_TEXT_DELTA::equals).count());
        events.stream().limit(10).forEach(event ->
                System.out.println("[T9-chat] SSE " + event.name() + " data(前 160 字): "
                        + truncate(event.data(), 160)));
        System.out.println("[T9-chat] SSE FINAL.answer(前 200 字): "
                + truncate(finalData.path("answer").asText(), 200));
        System.out.println("[T9-chat] SSE FINAL.sql: " + finalData.path("sql").asText()
                + " rows=" + finalData.path("rows").size() + "行");
    }

    /** SSE 解析产物：事件名 + data（多行 data 以 \n 连接）。 */
    private record SseEvent(String name, String data) {
    }

    /** 测试内注册的登录会话：用户名 + token（token 只在进程内传递，不落盘、不进报告）。 */
    private record Session(String username, String token) {
    }

    /** 注册随机用户并登录（受保护接口需要 JWT）；随机名避免与既有数据冲突。 */
    private Session registerAndLogin() {
        String username = "t10c" + System.currentTimeMillis() + (int) (Math.random() * 1000);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ApiResponse<String>> registered = restTemplate.exchange(
                "/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD), headers),
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, registered.getBody() == null ? -1 : registered.getBody().getCode(),
                "测试用户注册应成功: " + (registered.getBody() == null ? "无 body" : registered.getBody().getMessage()));

        ResponseEntity<ApiResponse<LoginResponse>> login = restTemplate.exchange(
                "/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD), headers),
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, login.getBody() == null ? -1 : login.getBody().getCode(),
                "测试用户登录应成功: " + (login.getBody() == null ? "无 body" : login.getBody().getMessage()));
        LoginResponse data = login.getBody() == null ? null : login.getBody().getData();
        assertNotNull(data, "登录 data 不应为空");
        assertNotNull(data.getToken(), "token 不应为空");
        System.out.println("[T10-auth] 聊天用例用户: username=" + username
                + " token(前12字符)=" + truncateToken(data.getToken()));
        return new Session(username, data.getToken());
    }

    /** 用户名 → userId（审计 assert 用）。 */
    private Long userIdOf(String username) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        assertNotNull(user, "注册用户应能在 users 表查到: " + username);
        return user.getId();
    }

    /** 留证用 token 截断（前 12 字符）。 */
    private static String truncateToken(String token) {
        if (token == null) {
            return "(null)";
        }
        return token.length() <= 12 ? token + "..." : token.substring(0, 12) + "...";
    }

    /** 读 SSE 事件流直到 EOF（服务端在 FINAL 后关闭），按空行分隔事件。 */
    private List<SseEvent> readAllEvents(InputStream body) throws Exception {
        List<SseEvent> events = new ArrayList<>();
        String eventName = null;
        StringBuilder data = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (eventName != null || data.length() > 0) {
                        events.add(new SseEvent(eventName == null ? "(无 event 名)" : eventName, data.toString()));
                    }
                    eventName = null;
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
                // id: / retry: / 注释行忽略
            }
        }
        return events;
    }

    /** MySQL 与本地 Ollama 都在才执行（与 AgentE2ESupport.assumeLocalStackUp 模式一致）。 */
    private void assumeLocalStackUp() {
        Assumptions.assumeTrue(portReachable(3306), "本机 MySQL 未启动，跳过聊天接口集成验证");
        Assumptions.assumeTrue(portReachable(11434), "本机 Ollama 未启动，跳过聊天接口集成验证");
    }

    /** 按库名定位 nlp2sql_test 数据源 id；查不到直接断言失败并提示补记录。 */
    private Long nlp2sqlTestDatasourceId() {
        DataSource ds = dataSourceMapper.selectOne(new LambdaQueryWrapper<DataSource>()
                .eq(DataSource::getDatabaseName, TARGET_DATABASE)
                .orderByAsc(DataSource::getId)
                .last("LIMIT 1"));
        assertNotNull(ds, "nlp2sql_meta.datasources 中缺少 database_name='" + TARGET_DATABASE
                + "' 的记录，请先在本机元数据库补一条指向该库的 mysql 数据源");
        return ds.getId();
    }

    private static boolean portReachable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "(null)";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
