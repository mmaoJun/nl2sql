package com.nl2sql.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.mapper.ChatSessionMapper;
import com.nl2sql.mapper.ChatTurnMapper;
import com.nl2sql.model.ChatTurn;
import com.nl2sql.model.dto.ApiResponse;
import com.nl2sql.model.dto.LoginResponse;
import com.nl2sql.model.vo.SessionVO;
import com.nl2sql.model.vo.TurnVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话 HTTP 接口集成验证（{@code /api/v1/sessions}，真实 HTTP + 本机 MySQL；<b>零 LLM 调用</b>）。
 *
 * <p>断言矩阵（T12 前端按此对接）：
 * <ol>
 *   <li>POST 新建（空标题 → 占位"新对话"）→ GET 列表可见 → PATCH 改名 → GET 轮次（空）→ DELETE；</li>
 *   <li>他人会话访问被拒：轮次 / 改名 / 删除三个入口都返回业务码 3001（HTTP 200）；</li>
 *   <li>无 token 访问返回 403（过滤链拦截，与 T10 一致）；</li>
 *   <li>重命名标题为空 → {@code @Valid} 拦截（HTTP 400）。</li>
 * </ol>
 *
 * <p>测试用户随机注册，用例结束清理自己造的会话与轮次（不给本机库留垃圾）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionIntegrationTest {

    private static final String PASSWORD = "t11-session-pass";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ChatSessionMapper sessionMapper;

    @Autowired
    private ChatTurnMapper turnMapper;

    /** 本用例创建的会话 id，结束后清理 */
    private final List<Long> createdSessionIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long sessionId : createdSessionIds) {
            turnMapper.delete(new LambdaQueryWrapper<ChatTurn>().eq(ChatTurn::getSessionId, sessionId));
            sessionMapper.deleteById(sessionId);
        }
        createdSessionIds.clear();
    }

    /** 会话 CRUD 全链路（本人操作）。 */
    @Test
    void sessionCrudEndToEnd() {
        assumeMysqlUp();
        String token = registerAndLogin();

        // 1) 新建：标题可空 → 占位"新对话"，datasourceId 透传
        ApiResponse<SessionVO> created = post("/api/v1/sessions",
                Map.of("datasourceId", 5), token,
                new ParameterizedTypeReference<ApiResponse<SessionVO>>() {
                }).getBody();
        assertNotNull(created, "新建会话响应体不应为空");
        assertEquals(200, created.getCode(), "新建会话应成功: " + created.getMessage());
        SessionVO session = created.getData();
        assertNotNull(session, "新建会话 data 不应为空");
        assertNotNull(session.getId(), "新建会话应返回自增 id");
        assertEquals("新对话", session.getTitle(), "未给标题应为占位标题");
        assertEquals(5L, session.getDatasourceId(), "默认数据源应透传");
        createdSessionIds.add(session.getId());

        // 2) 列表：本人可见
        ApiResponse<List<SessionVO>> list = get("/api/v1/sessions", token,
                new ParameterizedTypeReference<ApiResponse<List<SessionVO>>>() {
                }).getBody();
        assertNotNull(list, "列表响应体不应为空");
        assertEquals(200, list.getCode(), "列表应成功");
        assertTrue(list.getData().stream().anyMatch(vo -> session.getId().equals(vo.getId())),
                "新建的会话应出现在本人列表里");

        // 3) 轮次：新建会话还没问过，返回空列表
        ApiResponse<List<TurnVO>> turns = get("/api/v1/sessions/" + session.getId() + "/turns", token,
                new ParameterizedTypeReference<ApiResponse<List<TurnVO>>>() {
                }).getBody();
        assertNotNull(turns, "轮次响应体不应为空");
        assertEquals(200, turns.getCode(), "查轮次应成功");
        assertTrue(turns.getData().isEmpty(), "新会话不应有轮次");

        // 4) 改名
        ApiResponse<SessionVO> renamed = exchange(HttpMethod.PATCH,
                "/api/v1/sessions/" + session.getId(), Map.of("title", "T11 会话用例"),
                token, new ParameterizedTypeReference<ApiResponse<SessionVO>>() {
                }).getBody();
        assertNotNull(renamed, "改名响应体不应为空");
        assertEquals(200, renamed.getCode(), "改名应成功: " + renamed.getMessage());
        assertEquals("T11 会话用例", renamed.getData().getTitle(), "改名后应返回新标题");
        assertEquals("T11 会话用例", sessionMapper.selectById(session.getId()).getTitle(), "改名应落库");

        // 5) 删除（级联轮次）→ 再查变 3001
        ApiResponse<Void> deleted = exchange(HttpMethod.DELETE, "/api/v1/sessions/" + session.getId(),
                null, token, new ParameterizedTypeReference<ApiResponse<Void>>() {
                }).getBody();
        assertNotNull(deleted, "删除响应体不应为空");
        assertEquals(200, deleted.getCode(), "删除应成功: " + deleted.getMessage());
        createdSessionIds.remove(session.getId());
        assertNull(sessionMapper.selectById(session.getId()), "删除后会话行应不存在");

        ApiResponse<List<TurnVO>> afterDelete = get("/api/v1/sessions/" + session.getId() + "/turns", token,
                new ParameterizedTypeReference<ApiResponse<List<TurnVO>>>() {
                }).getBody();
        assertEquals(3001, afterDelete == null ? -1 : afterDelete.getCode(),
                "删除后查轮次应返回 3001（会话不存在）");
        System.out.println("[T11-session-http] CRUD ok: sessionId=" + session.getId()
                + " 新建→列表→轮次(空)→改名→删除→再查 3001");
    }

    /** 越权与未认证：他人会话三个入口一律 3001；无 token 403；空标题 400。 */
    @Test
    void otherUsersSessionIsRejected() {
        assumeMysqlUp();
        String ownerToken = registerAndLogin();
        String otherToken = registerAndLogin();

        ApiResponse<SessionVO> created = post("/api/v1/sessions", Map.of(), ownerToken,
                new ParameterizedTypeReference<ApiResponse<SessionVO>>() {
                }).getBody();
        assertNotNull(created, "新建会话响应体不应为空");
        assertEquals(200, created.getCode(), "新建会话应成功: " + created.getMessage());
        Long sessionId = created.getData().getId();
        createdSessionIds.add(sessionId);

        ApiResponse<List<TurnVO>> turnsDenied = get("/api/v1/sessions/" + sessionId + "/turns", otherToken,
                new ParameterizedTypeReference<ApiResponse<List<TurnVO>>>() {
                }).getBody();
        assertEquals(3001, turnsDenied == null ? -1 : turnsDenied.getCode(), "他人查轮次应 3001");

        ApiResponse<SessionVO> renameDenied = exchange(HttpMethod.PATCH, "/api/v1/sessions/" + sessionId,
                Map.of("title", "越权"), otherToken,
                new ParameterizedTypeReference<ApiResponse<SessionVO>>() {
                }).getBody();
        assertEquals(3001, renameDenied == null ? -1 : renameDenied.getCode(), "他人改名应 3001");

        ApiResponse<Void> deleteDenied = exchange(HttpMethod.DELETE, "/api/v1/sessions/" + sessionId,
                null, otherToken, new ParameterizedTypeReference<ApiResponse<Void>>() {
                }).getBody();
        assertEquals(3001, deleteDenied == null ? -1 : deleteDenied.getCode(), "他人删除应 3001");
        assertNotNull(sessionMapper.selectById(sessionId), "越权删除后会话必须还在");

        // 无 token：过滤链拦截（T10 口径：Spring Security 默认入口点返回 403）
        ResponseEntity<String> anonymous = restTemplate.getForEntity("/api/v1/sessions", String.class);
        assertEquals(403, anonymous.getStatusCode().value(), "无 token 访问会话接口应 403");

        // 空标题：@Valid 拦截（HTTP 400，与旧实现一致）
        ResponseEntity<String> blankTitle = exchangeRaw(HttpMethod.PATCH, "/api/v1/sessions/" + sessionId,
                Map.of("title", "  "), ownerToken);
        assertEquals(400, blankTitle.getStatusCode().value(), "空标题改名应被 @Valid 拦成 HTTP 400");

        System.out.println("[T11-session-http] 越权/未认证 ok: sessionId=" + sessionId
                + " 他人查/改/删=3001，无 token=403，空标题=400");
    }

    /** 会话列表只含本人会话（第二个用户看不到第一个用户的会话）。 */
    @Test
    void listIsolatedBetweenUsers() {
        assumeMysqlUp();
        String ownerToken = registerAndLogin();
        String otherToken = registerAndLogin();

        ApiResponse<SessionVO> created = post("/api/v1/sessions", Map.of(), ownerToken,
                new ParameterizedTypeReference<ApiResponse<SessionVO>>() {
                }).getBody();
        assertNotNull(created, "新建会话响应体不应为空");
        Long sessionId = created.getData().getId();
        createdSessionIds.add(sessionId);

        ApiResponse<List<SessionVO>> otherList = get("/api/v1/sessions", otherToken,
                new ParameterizedTypeReference<ApiResponse<List<SessionVO>>>() {
                }).getBody();
        assertNotNull(otherList, "列表响应体不应为空");
        assertFalse(otherList.getData().stream().anyMatch(vo -> sessionId.equals(vo.getId())),
                "他人列表不应包含该会话");
        System.out.println("[T11-session-http] 列表隔离 ok: sessionId=" + sessionId + " 未出现在他人列表");
    }

    /** 注册随机用户并登录，返回 token。 */
    private String registerAndLogin() {
        String username = "t11s" + System.currentTimeMillis() + (int) (Math.random() * 1000);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

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
        return data.getToken();
    }

    private <T> ResponseEntity<T> post(String path, Object body, String token,
                                       ParameterizedTypeReference<T> type) {
        return exchange(HttpMethod.POST, path, body, token, type);
    }

    private <T> ResponseEntity<T> get(String path, String token, ParameterizedTypeReference<T> type) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), type);
    }

    private <T> ResponseEntity<T> exchange(HttpMethod method, String path, Object body, String token,
                                           ParameterizedTypeReference<T> type) {
        return restTemplate.exchange(path, method, new HttpEntity<>(body, authHeaders(token)), type);
    }

    private ResponseEntity<String> exchangeRaw(HttpMethod method, String path, Object body, String token) {
        return restTemplate.exchange(path, method, new HttpEntity<>(body, authHeaders(token)), String.class);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    /** MySQL 可达才执行（与既有集成用例同一守卫模式）。 */
    private void assumeMysqlUp() {
        Assumptions.assumeTrue(portReachable(3306), "本机 MySQL 未启动，跳过会话接口集成验证");
    }

    private static boolean portReachable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
