package com.nl2sql.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.common.TurnState;
import com.nl2sql.mapper.AuditLogMapper;
import com.nl2sql.mapper.ChatSessionMapper;
import com.nl2sql.mapper.ChatTurnMapper;
import com.nl2sql.mapper.DataSourceMapper;
import com.nl2sql.mapper.UserMapper;
import com.nl2sql.model.AuditLog;
import com.nl2sql.model.ChatTurn;
import com.nl2sql.model.DataSource;
import com.nl2sql.model.User;
import com.nl2sql.model.dto.ApiResponse;
import com.nl2sql.model.dto.LoginResponse;
import com.nl2sql.model.vo.ChatResult;
import com.nl2sql.model.vo.ChatStreamEvent;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聊天 HTTP 通道集成验证（真实本地模型：MySQL + Ollama 均可达才执行）。
 *
 * <p>两例各一次完整问答（真实 LLM 预算上限即此两问）：
 * <ol>
 *   <li>同步 {@code POST /api/v1/chat}：断言 answer 非空、rows 非空、latencyMs &gt; 0；
 *       并验证 T10 审计——本轮问答在 {@code audit_logs} 落一条对应用户的记录；
 *       T11 起顺带断言会话归因（审计 session_id/turn_id 非空）与轮次落库（answer 与结果一致）；</li>
 *   <li>SSE {@code GET /api/v1/chat/stream}：收集事件流直到服务端关闭，
 *       断言出现 TOOL_CALL / TOOL_RESULT（且调用先于结果）并以 FINAL 收尾，
 *       FINAL 载荷（ChatResult）answer 非空、rows 非空、sessionId 非空。</li>
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

    @Autowired
    private ChatTurnMapper chatTurnMapper;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    /** T25 断线用例自动新建的会话 id，结束后清理（连同轮次） */
    private Long createdSessionId;

    @AfterEach
    void cleanup() {
        if (createdSessionId != null) {
            chatTurnMapper.delete(new LambdaQueryWrapper<ChatTurn>()
                    .eq(ChatTurn::getSessionId, createdSessionId));
            chatSessionMapper.deleteById(createdSessionId);
            createdSessionId = null;
        }
    }

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
        // T11：未带 sessionId 的问答由服务端自动建会话并落轮次，审计随之带上归因
        assertNotNull(audit.getSessionId(), "T11 起审计应带会话归因（自动新建会话）");
        assertNotNull(audit.getTurnId(), "T11 起审计应带轮次归因");
        assertNotNull(audit.getCreatedAt(), "审计 createdAt 应由自动填充写入");

        // T11 轮次落库：审计指向的轮次应存在，且 answer 与本次结果一致
        ChatTurn turn = chatTurnMapper.selectById(audit.getTurnId());
        assertNotNull(turn, "审计 turn_id 指向的轮次应落库: " + audit.getTurnId());
        assertEquals(audit.getSessionId(), turn.getSessionId(), "轮次应挂在审计的会话下");
        assertEquals(question, turn.getQuestion(), "轮次 question 应为用户原话");
        assertEquals(result.getAnswer(), turn.getAnswer(), "轮次 answer 应与本次回答一致");
        assertEquals(datasourceId, turn.getDatasourceId(), "轮次应记本轮数据源");

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
        assertTrue(finalData.path("sessionId").asLong() > 0,
                "FINAL.sessionId 应为自动新建的会话 id（T11 契约增量，T12 依赖）");

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

    /**
     * SSE 客户端中途断开（T25 项 1）：读满首个事件后主动关闭连接，
     * 断言取消/断线路径同样落一条 {@code chat_turns}（status=cancelled）+ 一条审计。
     *
     * <p>构造方式：{@link HttpClient} 流式 body（{@code ofInputStream}）——响应到达后
     * 读满首个 SSE 事件块（空行为界）即 {@code body.close()}，模拟"关页签 / 网络断开"。
     * 底层连接关闭后，服务端下一次写事件即失败，Spring MVC 随之取消 Flux 订阅，
     * 触发 {@code doFinally(CANCEL)} 收尾（无需等模型生成完，取消窗口足够短）。
     *
     * <p>T25 前该场景轮次与审计全部丢失；断言以"轮询等待落库"容忍取消检测的异步延迟。
     * 本用例消耗 1 次真实 LLM 预算（问题短、中断早，实际只跑出首个事件）。
     */
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void sseClientDisconnectStillRecordsCancelledTurnAndAudit() throws Exception {
        assumeLocalStackUp();
        Long datasourceId = nlp2sqlTestDatasourceId();
        Session session = registerAndLogin();
        Long userId = userIdOf(session.username());

        // 问题带唯一令牌：按问题文本轮询本用例的落库行，避免与历史/其他用例混淆
        String question = "orders 表中共有多少条订单记录？（T25 断线用例 " + System.nanoTime() + "）";
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

        // 读满首个事件块后主动断开（不读到 FINAL，保证取消发生在流中途）
        InputStream body = response.body();
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        SseEvent firstEvent = readOneEvent(reader);
        assertNotNull(firstEvent, "SSE 流应至少产出一个事件");
        assertNotEquals(ChatStreamEvent.EVENT_FINAL, firstEvent.name(),
                "首个事件不应是 FINAL（否则取消窗口不成立），实际: " + firstEvent.name());
        body.close();
        System.out.println("[T25-cancel] 已读首个事件并断开连接: event=" + firstEvent.name()
                + " data(前 120 字)=" + truncate(firstEvent.data(), 120));

        // 轮询等待取消路径收尾落库（取消检测与收尾是异步的）
        ChatTurn turn = awaitTurnByQuestion(question, Duration.ofSeconds(60));
        createdSessionId = turn.getSessionId();

        // ── chat_turns：一条 cancelled 轮次，字段口径对齐失败路径 ──
        assertEquals(TurnState.TURN_CANCELLED, turn.getStatus(),
                "断线后应落 cancelled 轮次，实际: " + turn.getStatus());
        assertNotNull(turn.getSessionId(), "cancelled 轮次应挂在自动新建的会话下");
        assertEquals(question, turn.getQuestion(), "轮次 question 应为用户原话");
        assertEquals(datasourceId, turn.getDatasourceId(), "轮次应记本轮数据源");
        assertNotNull(turn.getErrorMessage(), "取消原因应写入 error_message");
        assertTrue(turn.getErrorMessage().contains("取消") || turn.getErrorMessage().contains("中断"),
                "取消原因应注明客户端取消/连接中断，实际: " + turn.getErrorMessage());
        assertEquals("ollama", turn.getModelProvider(), "本地通道 provider 应为 ollama");
        assertNotNull(turn.getExecutionTimeMs(), "取消轮次应记耗时");
        assertTrue(turn.getExecutionTimeMs() > 0, "取消轮次耗时应大于 0");

        // ── audit_logs：对应用户最新一条审计，归因与取消语义一致 ──
        AuditLog audit = auditLogMapper.selectOne(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getUserId, userId)
                .orderByDesc(AuditLog::getId)
                .last("LIMIT 1"));
        assertNotNull(audit, "取消路径应落一条审计");
        assertEquals(question, audit.getNlInput(), "审计 nlInput 应为用户原话");
        assertEquals(datasourceId, audit.getDatasourceId(), "审计 datasourceId 应为本轮数据源");
        assertEquals(turn.getSessionId(), audit.getSessionId(), "审计应带会话归因");
        assertEquals(turn.getId(), audit.getTurnId(), "审计应带轮次归因");
        assertEquals(Boolean.FALSE, audit.getExecutionSuccess(), "取消审计 executionSuccess 应为 false");
        assertEquals(Boolean.FALSE, audit.getValidationPassed(),
                "取消审计 validationPassed 应为 false（无成功的 execute_sql）");
        assertEquals(turn.getErrorMessage(), audit.getErrorMessage(), "轮次与审计的取消原因应一致");

        System.out.println("[T25-cancel] turn#" + turn.getId() + " sessionId=" + turn.getSessionId()
                + " status=" + turn.getStatus() + " errorMessage=" + turn.getErrorMessage()
                + " executionTimeMs=" + turn.getExecutionTimeMs());
        System.out.println("[T25-cancel] audit#" + audit.getId() + " userId=" + audit.getUserId()
                + " sessionId=" + audit.getSessionId() + " turnId=" + audit.getTurnId()
                + " executionSuccess=" + audit.getExecutionSuccess()
                + " validationPassed=" + audit.getValidationPassed());
    }

    /** 读一个完整 SSE 事件块（空行为界）；流在事件完成前结束时返回已读内容或 null。 */
    private SseEvent readOneEvent(BufferedReader reader) throws Exception {
        String eventName = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (eventName != null || data.length() > 0) {
                    return new SseEvent(eventName == null ? "(无 event 名)" : eventName, data.toString());
                }
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
        return (eventName != null || data.length() > 0)
                ? new SseEvent(eventName == null ? "(无 event 名)" : eventName, data.toString())
                : null;
    }

    /**
     * 按问题文本轮询最新轮次（取到即返回，status 为取消路径落库）。
     *
     * @param timeout 轮询上限（取消检测与收尾是异步的，秒级延迟属正常）
     * @throws AssertionError 超时未见落库时抛出（带问题文本便于定位）
     */
    private ChatTurn awaitTurnByQuestion(String question, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        ChatTurn turn = null;
        while (System.nanoTime() < deadline) {
            turn = chatTurnMapper.selectOne(new LambdaQueryWrapper<ChatTurn>()
                    .eq(ChatTurn::getQuestion, question)
                    .orderByDesc(ChatTurn::getId)
                    .last("LIMIT 1"));
            if (turn != null && turn.getStatus() != null) {
                return turn;
            }
            Thread.sleep(500);
        }
        assertNotNull(turn, "轮询超时：断线的轮次未落库，question=" + question);
        return turn;
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
