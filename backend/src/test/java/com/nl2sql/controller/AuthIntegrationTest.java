package com.nl2sql.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.mapper.UserMapper;
import com.nl2sql.model.User;
import com.nl2sql.model.dto.ApiResponse;
import com.nl2sql.model.dto.LoginRequest;
import com.nl2sql.model.dto.LoginResponse;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 认证集成验证（真实 HTTP + 本机 MySQL；不触发任何 LLM 调用）。
 *
 * <p>断言矩阵（对齐旧项目行为）：
 * <ol>
 *   <li>注册（随机用户名，避免与既有数据冲突）→ 登录拿 token（留证时截断）
 *       → 带 token 访问受保护接口<b>通过认证</b>（用 chat 入口的非法参数路径验证：
 *       认证在过滤链、参数校验在 MVC，两者都早于 ChatService，不消耗真实模型预算）；</li>
 *   <li>重复注册被拒（400）；错密码 / 不存在的用户被拒（业务码 401，HTTP 200）；</li>
 *   <li>无 token / 伪造 token 访问受保护接口被 Spring Security 拒绝（断言实际 HTTP 状态码）；</li>
 *   <li>放行清单：{@code /api/v1/health} 与 {@code /api/v1/auth/**} 匿名可用；</li>
 *   <li>T9 遗留修复：chat 缺 body / 非法 JSON 返回 4001（不再是 500/9999）。</li>
 * </ol>
 *
 * @see com.nl2sql.config.SecurityConfig
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {

    /** 受保护接口（anyRequest().authenticated()） */
    private static final String PROTECTED_SYNC = "/api/v1/chat";
    private static final String PROTECTED_SSE = "/api/v1/chat/stream";

    private static final String PASSWORD = "t10-pass-123456";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserMapper userMapper;

    /** 注册 → 登录 → 带 token 访问受保护接口；顺带覆盖重复注册与 health 放行。 */
    @Test
    void registerLoginThenAccessProtectedEndpoints() {
        assumeMysqlUp();
        String username = randomUsername();

        ApiResponse<String> registered = register(username, PASSWORD);
        assertEquals(200, registered.getCode(), "注册应成功，实际: " + registered.getMessage());
        System.out.println("[T10-auth] 注册: username=" + username + " -> " + registered.getMessage());

        User stored = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        assertNotNull(stored, "注册后用户应落库");
        assertEquals("USER", stored.getRole(), "默认角色应为 USER");
        assertTrue(stored.getPassword() != null && stored.getPassword().startsWith("$2"),
                "密码应为 BCrypt 哈希（$2a/$2b 前缀）");

        ApiResponse<String> duplicate = register(username, PASSWORD);
        assertEquals(400, duplicate.getCode(), "重复注册应被拒（400），实际: " + duplicate.getCode());
        System.out.println("[T10-auth] 重复注册被拒: code=" + duplicate.getCode()
                + " message=" + duplicate.getMessage());

        ApiResponse<LoginResponse> login = login(username, PASSWORD);
        assertEquals(200, login.getCode(), "登录应成功，实际: " + login.getMessage());
        LoginResponse data = login.getData();
        assertNotNull(data, "登录 data 不应为空");
        assertNotNull(data.getToken(), "token 不应为空");
        assertEquals(username, data.getUsername());
        assertEquals("USER", data.getRole(), "默认角色应为 USER");
        String token = data.getToken();
        System.out.println("[T10-auth] 登录: username=" + data.getUsername() + " role=" + data.getRole()
                + " token(前12字符)=" + truncateToken(token));

        // 放行清单：health 匿名可用
        ResponseEntity<ApiResponse<String>> health = restTemplate.exchange(
                "/api/v1/health", HttpMethod.GET, HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, health.getStatusCode().value(), "health 放行应返回 200");
        assertEquals("ok", health.getBody() == null ? null : health.getBody().getData());

        // 带 token：受保护接口通过认证（参数不合法 → 业务码 4001，说明已进入控制器）
        ResponseEntity<ApiResponse<Void>> withToken = postJson(PROTECTED_SYNC, "{}", token);
        assertEquals(200, withToken.getStatusCode().value(), "带 token 的请求应通过过滤链（HTTP 200）");
        assertEquals(4001, withToken.getBody() == null ? -1 : withToken.getBody().getCode(),
                "参数不合法应为 4001，实际: " + (withToken.getBody() == null ? "无 body" : withToken.getBody()));

        // 带 token + 非法 JSON：T9 遗留修复（4001 而不是 500）
        ResponseEntity<ApiResponse<Void>> badJson = postJson(PROTECTED_SYNC, "{not-json", token);
        assertEquals(200, badJson.getStatusCode().value(), "非法 JSON 应返回 HTTP 200 + 业务码");
        assertNotNull(badJson.getBody());
        assertEquals(4001, badJson.getBody().getCode(), "非法 JSON 应为 4001，实际: " + badJson.getBody().getCode());

        // 带 token + 缺 body：同样 4001
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        jsonHeaders.setBearerAuth(token);
        ResponseEntity<ApiResponse<Void>> noBody = restTemplate.exchange(
                PROTECTED_SYNC, HttpMethod.POST, new HttpEntity<>(null, jsonHeaders),
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, noBody.getStatusCode().value(), "缺 body 应返回 HTTP 200 + 业务码");
        assertNotNull(noBody.getBody());
        assertEquals(4001, noBody.getBody().getCode(), "缺 body 应为 4001，实际: " + noBody.getBody().getCode());

        // 带 token 的 SSE 入口：认证通过后由参数校验拦截（业务码 4001，流未开始）
        // Accept 同时带 text/event-stream 与 application/json：前者命中 produces=text/event-stream 的映射，
        // 后者让"流未开始"的 JSON 错误体可被协商写出（只带 text/event-stream 时拿不到 JSON，见报告遗留问题）
        HttpHeaders sseHeaders = new HttpHeaders();
        sseHeaders.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
        sseHeaders.setBearerAuth(token);
        ResponseEntity<ApiResponse<Void>> sseNoParams = restTemplate.exchange(
                PROTECTED_SSE, HttpMethod.GET, new HttpEntity<>(null, sseHeaders),
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, sseNoParams.getStatusCode().value(),
                "带 token 的 SSE 入口应通过过滤链，实际: " + sseNoParams.getStatusCode().value());
        assertNotNull(sseNoParams.getBody());
        assertEquals(4001, sseNoParams.getBody().getCode(),
                "SSE 参数不合法应为 4001，实际: " + sseNoParams.getBody().getCode());
        System.out.println("[T10-auth] 带 token GET " + PROTECTED_SSE + "(缺参数) -> HTTP "
                + sseNoParams.getStatusCode().value() + " code=" + sseNoParams.getBody().getCode());

        System.out.println("[T10-auth] 带 token 访问受保护接口: HTTP 200 + 业务码 4001（认证通过、参数校验拦截，未触发模型）");
        System.out.println("[T10-auth] 缺 body / 非法 JSON: 4001（T9 遗留已修复）");
    }

    /** 错密码 / 不存在的用户：业务码 401（HTTP 200，与旧实现一致）。 */
    @Test
    void wrongPasswordAndUnknownUserAreRejected() {
        assumeMysqlUp();
        String username = randomUsername();
        assertEquals(200, register(username, PASSWORD).getCode());

        ApiResponse<LoginResponse> wrong = login(username, "wrong-password");
        assertEquals(401, wrong.getCode(), "错密码应返回 401，实际: " + wrong.getCode());
        assertEquals("用户名或密码错误", wrong.getMessage());

        ApiResponse<LoginResponse> unknown = login("t10u-missing-" + System.nanoTime(), PASSWORD);
        assertEquals(401, unknown.getCode(), "不存在的用户应返回 401，实际: " + unknown.getCode());
        System.out.println("[T10-auth] 错密码 / 不存在用户被拒: code=401 message=" + wrong.getMessage());
    }

    /** 无 token / 伪造 token 访问受保护接口：被 Spring Security 拒绝（断言实际状态码）。 */
    @Test
    void anonymousRequestIsRejected() {
        assumeMysqlUp();

        ResponseEntity<String> anonymous = restTemplate.exchange(
                PROTECTED_SYNC, HttpMethod.POST, HttpEntity.EMPTY, String.class);
        assertEquals(rejectionStatus(), anonymous.getStatusCode().value(),
                "无 token 访问受保护接口应被拒，实际: " + anonymous.getStatusCode().value());

        ResponseEntity<String> anonymousSse = restTemplate.exchange(
                PROTECTED_SSE, HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertEquals(rejectionStatus(), anonymousSse.getStatusCode().value(),
                "无 token 访问 SSE 应被拒，实际: " + anonymousSse.getStatusCode().value());

        HttpHeaders forged = new HttpHeaders();
        forged.setBearerAuth("not.a.jwt.token");
        ResponseEntity<String> fakeToken = restTemplate.exchange(
                PROTECTED_SYNC, HttpMethod.POST, new HttpEntity<>(null, forged), String.class);
        assertEquals(rejectionStatus(), fakeToken.getStatusCode().value(),
                "伪造 token 应被拒，实际: " + fakeToken.getStatusCode().value());

        // 反向对照：同一实例上放行接口匿名可用（排除"整个应用都拒绝"的误判）
        ResponseEntity<String> health = restTemplate.exchange(
                "/api/v1/health", HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertEquals(200, health.getStatusCode().value(), "health 应匿名可访问");

        System.out.println("[T10-auth] 无 token / 伪造 token 访问受保护接口: HTTP " + rejectionStatus()
                + "（Spring Security 默认入口点）；health 匿名 200");
    }

    /**
     * 未认证请求被拒的实际状态码。
     *
     * <p>旧项目 SecurityConfig 未配置 exceptionHandling，Spring Security 6 的默认
     * {@code Http403ForbiddenEntryPoint} 返回 <b>403</b>（不是 401）；本任务保持该行为，
     * 集成断言按实际状态码校验。
     */
    private static int rejectionStatus() {
        return 403;
    }

    /** 注册（业务响应体）。 */
    private ApiResponse<String> register(String username, String password) {
        ResponseEntity<ApiResponse<String>> response = restTemplate.exchange(
                "/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(credentials(username, password), jsonHeaders()),
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, response.getStatusCode().value(), "注册接口 HTTP 状态应为 200");
        assertNotNull(response.getBody());
        return response.getBody();
    }

    /** 登录（业务响应体）。 */
    private ApiResponse<LoginResponse> login(String username, String password) {
        ResponseEntity<ApiResponse<LoginResponse>> response = restTemplate.exchange(
                "/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(credentials(username, password), jsonHeaders()),
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, response.getStatusCode().value(), "登录接口 HTTP 状态应为 200");
        assertNotNull(response.getBody());
        return response.getBody();
    }

    /** 带 token 的 JSON POST（raw body 以字符串直传，便于构造非法 JSON）。 */
    private ResponseEntity<ApiResponse<Void>> postJson(String path, String rawJson, String token) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(rawJson, headers),
                new ParameterizedTypeReference<>() {
                });
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static Map<String, String> credentials(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return Map.of("username", request.getUsername(), "password", request.getPassword());
    }

    /** 随机用户名：避免与既有用户冲突，也不污染固定数据。 */
    private static String randomUsername() {
        return "t10u" + System.currentTimeMillis() + (int) (Math.random() * 1000);
    }

    /** 留证用 token 截断（前 12 字符）——报告与日志中不出现完整 token。 */
    private static String truncateToken(String token) {
        if (token == null) {
            return "(null)";
        }
        return token.length() <= 12 ? token + "..." : token.substring(0, 12) + "...";
    }

    /** MySQL 可达才执行（与 ChatControllerIntegrationTest 的环境守卫同一模式）。 */
    private void assumeMysqlUp() {
        Assumptions.assumeTrue(portReachable(3306), "本机 MySQL 未启动，跳过认证集成验证");
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
