package com.nl2sql.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.common.TurnState;
import com.nl2sql.mapper.AuditLogMapper;
import com.nl2sql.mapper.ChatSessionMapper;
import com.nl2sql.mapper.ChatTurnMapper;
import com.nl2sql.mapper.DataSourceMapper;
import com.nl2sql.mapper.UserMapper;
import com.nl2sql.model.AuditLog;
import com.nl2sql.model.ChatSession;
import com.nl2sql.model.ChatTurn;
import com.nl2sql.model.DataSource;
import com.nl2sql.model.User;
import com.nl2sql.model.dto.ApiResponse;
import com.nl2sql.model.dto.LoginResponse;
import com.nl2sql.model.vo.AgentTraceItem;
import com.nl2sql.model.vo.ChatResult;
import com.nl2sql.model.vo.TurnVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.InetSocketAddress;
import java.net.Socket;
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
 * 会话记忆（多轮追问）端到端验证：真实本地模型，<b>全项目唯一消耗真实 LLM 预算的 T11 用例</b>。
 *
 * <p>场景（同一会话连续两问，第二问脱离历史就答不对）：
 * <ol>
 *   <li>第一问「users 表中共有多少位用户？」（不带 sessionId → 服务端自动新建会话）——
 *       断言 answer 命中真实数据 200、{@code ChatResult.sessionId} 非空、会话标题取首问；</li>
 *   <li>第二问「我刚才问的第一个问题是什么？」（带上第一问返回的 sessionId）——
 *       答案只存在于上一轮的对话历史里，重新查库也查不到，
 *       只有把最近轮次注入 Agent 才可能答对；</li>
 * </ol>
 *
 * <p>第二问设计说明（含一次模型行为修复）：初版设计为数值追问
 * （"orders 的订单数比刚才的用户数多多少"），实测本地 7B 模型<b>不调工具直接心算</b>
 * 并答错差值（500-200 答成 50）——历史注入本身已生效（模型能读到上一轮问答），
 * 失败点是模型算术行为。据"最多修 1 次"的约束改为<b>纯历史回放</b>型追问：
 * 不涉及算术与再查库，脱离历史必然答不出，断言更贴近"历史是否真的被注入"。
 *
 * <p>落库断言：{@code chat_turns} 恰好 2 条（字段逐一核对，含 {@code answer}），
 * 审计记录带 sessionId/turnId 归因，{@code GET /api/v1/sessions/{id}/turns} 按时间正序返回 2 轮。
 *
 * <p>环境守卫与 {@code ChatControllerIntegrationTest} 一致（MySQL + Ollama 端口探测）；
 * 数据源按库名 {@code nlp2sql_test} 定位，不硬编码 id。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionMemoryIntegrationTest {

    private static final String TARGET_DATABASE = "nlp2sql_test";

    /** 测试内注册用户所用密码（仅测试进程内使用） */
    private static final String PASSWORD = "t11-memory-pass";

    /** 第一问与期望的用户数（nlp2sql_test.users 真实行数） */
    private static final String QUESTION_1 = "users 表中共有多少位用户？";
    private static final String EXPECTED_COUNT_1 = "200";

    /**
     * 第二问：脱离历史无法回答的追问 —— 上一轮的提问内容只存在于会话历史里，
     * 库里查不到；期望回答里出现 "users"（或中文 "用户"）。
     */
    private static final String QUESTION_2 = "我刚才问的第一个问题是什么？";
    private static final String EXPECTED_KEYWORD_2 = "users";

    /**
     * 第二问答案的期望关键词：英文表名或中文说法皆可（模型可能用"用户表"复述）。
     */
    private static boolean secondAnswerHits(String answer) {
        return answer.toLowerCase().contains(EXPECTED_KEYWORD_2) || answer.contains("用户");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ChatSessionMapper sessionMapper;

    @Autowired
    private ChatTurnMapper turnMapper;

    @Autowired
    private AuditLogMapper auditLogMapper;

    /** 本轮创建的会话 id，结束后清理（连同轮次） */
    private Long createdSessionId;

    @AfterEach
    void cleanup() {
        if (createdSessionId != null) {
            turnMapper.delete(new LambdaQueryWrapper<ChatTurn>().eq(ChatTurn::getSessionId, createdSessionId));
            sessionMapper.deleteById(createdSessionId);
            createdSessionId = null;
        }
    }

    /** 两轮追问端到端：历史注入生效（第二问答对）+ 轮次落库 + 会话归因。 */
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void secondTurnUsesFirstTurnContext() {
        assumeLocalStackUp();
        Long datasourceId = nlp2sqlTestDatasourceId();
        Session login = registerAndLogin();
        Long userId = userIdOf(login.username());

        // ── 第一问：不带 sessionId，服务端自动新建会话 ──
        ChatResult first = chat(datasourceId, QUESTION_1, null, login.token());
        assertNotNull(first.getSessionId(), "自动新建会话后 ChatResult.sessionId 不应为空");
        createdSessionId = first.getSessionId();
        assertNotNull(first.getAnswer(), "第一问 answer 不应为 null");
        assertTrue(first.getAnswer().contains(EXPECTED_COUNT_1),
                "第一问应命中真实用户数 " + EXPECTED_COUNT_1 + "，实际回答: " + first.getAnswer());

        ChatSession session = sessionMapper.selectById(createdSessionId);
        assertNotNull(session, "自动新建的会话应落库");
        assertEquals(userId, session.getUserId(), "会话归属应为当前用户（服务端从 JWT 解析）");
        assertEquals(QUESTION_1, session.getTitle(), "会话标题应取首问");
        assertEquals(datasourceId, session.getDatasourceId(), "会话默认数据源应为本轮数据源");

        List<ChatTurn> turnsAfterFirst = turnsOf(createdSessionId);
        assertEquals(1, turnsAfterFirst.size(), "第一问后应有 1 条轮次");
        Long firstTurnId = turnsAfterFirst.get(0).getId();

        // ── 第二问：带上第一问返回的 sessionId（依赖第一轮上下文） ──
        ChatResult second = chat(datasourceId, QUESTION_2, createdSessionId, login.token());
        assertEquals(createdSessionId, second.getSessionId(), "带 sessionId 时不应另建会话");
        assertNotNull(second.getAnswer(), "第二问 answer 不应为 null");
        assertTrue(secondAnswerHits(second.getAnswer()),
                "第二问应基于第一轮历史答出上一轮问题（含 users / 用户），实际回答: " + second.getAnswer());

        // ── 落库断言：恰 2 轮，字段逐一核对（含 answer） ──
        List<ChatTurn> turns = turnsOf(createdSessionId);
        assertEquals(2, turns.size(), "两问后轮次表应有 2 条记录");
        ChatTurn firstTurn = turns.get(0);
        ChatTurn secondTurn = turns.get(1);
        assertEquals(firstTurnId, firstTurn.getId(), "轮次应按 id 正序返回");
        assertFalse(secondTurn.getId() <= firstTurn.getId(), "第二轮的 id 应更大（时间正序）");

        assertTurn(firstTurn, QUESTION_1, datasourceId, first.getAnswer(), EXPECTED_COUNT_1, true);
        assertTurn(secondTurn, QUESTION_2, datasourceId, second.getAnswer(), null, false);

        // ── 审计归因：最新一条审计应指向第二轮（sessionId + turnId） ──
        AuditLog audit = auditLogMapper.selectOne(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getUserId, userId)
                .orderByDesc(AuditLog::getId)
                .last("LIMIT 1"));
        assertNotNull(audit, "第二轮应落一条审计");
        assertEquals(createdSessionId, audit.getSessionId(), "审计应带会话归因");
        assertEquals(secondTurn.getId(), audit.getTurnId(), "审计应带轮次归因");

        // ── HTTP 契约：GET /api/v1/sessions/{id}/turns 时间正序返回 2 轮 ──
        ResponseEntity<ApiResponse<List<TurnVO>>> turnsResponse = restTemplate.exchange(
                "/api/v1/sessions/" + createdSessionId + "/turns", HttpMethod.GET,
                new HttpEntity<>(authHeaders(login.token())),
                new ParameterizedTypeReference<>() {
                });
        ApiResponse<List<TurnVO>> turnsApi = turnsResponse.getBody();
        assertNotNull(turnsApi, "轮次接口响应体不应为空");
        assertEquals(200, turnsApi.getCode(), "查轮次应成功: " + turnsApi.getMessage());
        assertEquals(2, turnsApi.getData().size(), "轮次接口应返回 2 轮");
        assertEquals(QUESTION_1, turnsApi.getData().get(0).getQuestion(), "接口应按时间正序返回");
        assertEquals(QUESTION_2, turnsApi.getData().get(1).getQuestion(), "第二轮回问题应一致");
        assertNotNull(turnsApi.getData().get(1).getAnswer(), "轮次接口应返回 answer（T12 展示用）");

        // ── 留证（报告用） ──
        System.out.println("[T11-memory] 会话: sessionId=" + createdSessionId + " userId=" + userId
                + " title=" + session.getTitle() + " datasourceId=" + datasourceId);
        printTurn("第1轮", QUESTION_1, first, firstTurn);
        printTurn("第2轮", QUESTION_2, second, secondTurn);
    }

    /**
     * 一轮问答的落库字段核对。
     *
     * @param sqlExpected           本轮是否必须跑成一次 SQL（第一问断言；第二问是纯历史回放，
     *                              模型可以不发起新查询 —— rowCount 允许为 0）
     * @param expectedAnswerFragment 期望出现在 answer 里的片段；null 表示由调用方另行断言
     */
    private void assertTurn(ChatTurn turn, String question, Long datasourceId, String expectedAnswer,
                            String expectedAnswerFragment, boolean sqlExpected) {
        assertEquals(question, turn.getQuestion(), "轮次 question 应为用户原话（不含数据源包装）");
        assertEquals(datasourceId, turn.getDatasourceId(), "轮次应记本轮数据源");
        assertEquals(expectedAnswer, turn.getAnswer(), "轮次 answer 应为本轮助手回答原文");
        assertNotNull(turn.getModelName(), "轮次 modelName 不应为空");
        assertEquals("ollama", turn.getModelProvider(), "本地通道 provider 应为 ollama");
        assertNotNull(turn.getExecutionTimeMs(), "轮次 executionTimeMs 不应为空");
        assertTrue(turn.getExecutionTimeMs() > 0, "轮次 executionTimeMs 应大于 0");
        assertEquals(TurnState.TURN_OK, turn.getStatus(), "正常回答的轮次状态应为 ok");
        assertNull(turn.getErrorMessage(), "成功轮次 errorMessage 应为空");
        assertNull(turn.getResolvedQuestion(), "不做改写：resolved_question 应为空");
        assertEquals("skipped", turn.getRewriteStatus(), "不做改写：rewrite_status 应取库默认 skipped");
        assertNotNull(turn.getCreatedAt(), "轮次 createdAt 应由自动填充写入");
        assertNotNull(turn.getRowCount(), "轮次 rowCount 不应为空（0 表示本轮没跑成 SQL）");
        assertTrue(turn.getRowCount() >= 0, "轮次 rowCount 不应为负");
        if (sqlExpected) {
            assertNotNull(turn.getGeneratedSql(), "轮次 generatedSql 不应为空");
            assertEquals(1, turn.getRowCount(), "单行 COUNT 查询的 rowCount 应为 1");
        }
        if (expectedAnswerFragment != null) {
            assertTrue(turn.getAnswer().contains(expectedAnswerFragment),
                    "轮次 answer 应含期望片段 " + expectedAnswerFragment);
        }
    }

    /** 打印一轮问答的轨迹摘要与落库字段（报告用，不含 token 等敏感值）。 */
    private void printTurn(String label, String question, ChatResult result, ChatTurn turn) {
        List<String> tools = result.getTrace().stream()
                .filter(item -> AgentTraceItem.TYPE_TOOL_CALL.equals(item.type()))
                .map(AgentTraceItem::tool)
                .toList();
        System.out.println("[T11-memory] " + label + " question=" + question);
        System.out.println("[T11-memory] " + label + " answer=" + result.getAnswer());
        System.out.println("[T11-memory] " + label + " sql=" + result.getSql() + " rowCount=" + result.getRowCount());
        System.out.println("[T11-memory] " + label + " tools=" + tools
                + " latencyMs=" + result.getLatencyMs() + " firstEventMs=" + result.getFirstEventMs());
        System.out.println("[T11-memory] turn#" + turn.getId() + " status=" + turn.getStatus()
                + " answer(前 60 字)=" + truncate(turn.getAnswer(), 60)
                + " rowCount=" + turn.getRowCount() + " executionTimeMs=" + turn.getExecutionTimeMs()
                + " model=" + turn.getModelName() + "/" + turn.getModelProvider()
                + " rewriteStatus=" + turn.getRewriteStatus() + " resolvedQuestion=" + turn.getResolvedQuestion()
                + " createdAt=" + turn.getCreatedAt());
    }

    /** 一次同步问答（sessionId 可空）。 */
    private ChatResult chat(Long datasourceId, String question, Long sessionId, String token) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("question", question);
        body.put("datasourceId", datasourceId);
        if (sessionId != null) {
            body.put("sessionId", sessionId);
        }
        ResponseEntity<ApiResponse<ChatResult>> response = restTemplate.exchange(
                "/api/v1/chat", HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)),
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, response.getStatusCode().value(), "HTTP 状态应为 200");
        ApiResponse<ChatResult> api = response.getBody();
        assertNotNull(api, "响应体不应为空");
        assertEquals(200, api.getCode(), "业务码应为 200，实际: " + api.getMessage());
        ChatResult result = api.getData();
        assertNotNull(result, "data 不应为空");
        return result;
    }

    private List<ChatTurn> turnsOf(Long sessionId) {
        return turnMapper.selectList(new LambdaQueryWrapper<ChatTurn>()
                .eq(ChatTurn::getSessionId, sessionId)
                .orderByAsc(ChatTurn::getId));
    }

    /** 测试内注册的登录会话：用户名 + token（token 只在进程内传递，不落盘、不进报告）。 */
    private record Session(String username, String token) {
    }

    /** 注册随机用户并登录（受保护接口需要 JWT）；随机名避免与既有数据冲突。 */
    private Session registerAndLogin() {
        String username = "t11m" + System.currentTimeMillis() + (int) (Math.random() * 1000);
        HttpHeaders headers = authHeaders(null);

        ResponseEntity<ApiResponse<String>> registered = restTemplate.exchange(
                "/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD), headers),
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, registered.getBody() == null ? -1 : registered.getBody().getCode(),
                "测试用户注册应成功");

        ResponseEntity<ApiResponse<LoginResponse>> login = restTemplate.exchange(
                "/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD), headers),
                new ParameterizedTypeReference<>() {
                });
        LoginResponse data = login.getBody() == null ? null : login.getBody().getData();
        assertNotNull(data, "登录 data 不应为空");
        assertNotNull(data.getToken(), "token 不应为空");
        return new Session(username, data.getToken());
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    /** 用户名 → userId（审计 assert 用）。 */
    private Long userIdOf(String username) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        assertNotNull(user, "注册用户应能在 users 表查到: " + username);
        return user.getId();
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

    /** MySQL 与本地 Ollama 都在才执行。 */
    private void assumeLocalStackUp() {
        Assumptions.assumeTrue(portReachable(3306), "本机 MySQL 未启动，跳过会话记忆集成验证");
        Assumptions.assumeTrue(portReachable(11434), "本机 Ollama 未启动，跳过会话记忆集成验证");
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
