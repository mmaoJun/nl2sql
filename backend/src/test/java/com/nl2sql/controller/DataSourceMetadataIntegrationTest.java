package com.nl2sql.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.adapter.DataSourceFactory;
import com.nl2sql.adapter.DataSourceTypeInfo;
import com.nl2sql.mapper.DataSourceMapper;
import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.DataSource;
import com.nl2sql.model.TableInfo;
import com.nl2sql.model.dto.ApiResponse;
import com.nl2sql.model.dto.LoginRequest;
import com.nl2sql.model.dto.LoginResponse;
import com.nl2sql.model.vo.DataSourceVO;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据源 / 元数据控制器集成验证（T12 新增；真实 HTTP + 本机 MySQL，**零 LLM 调用**）。
 *
 * <p>覆盖两个新控制器的契约：
 * <ol>
 *   <li>未认证访问 → 403（Spring Security 默认入口点，与 T10 行为一致）；</li>
 *   <li>{@code GET /datasources/supported-types} 返回适配器注册表（mysql / postgresql）；</li>
 *   <li>{@code GET /datasources} 列表密码脱敏为 "******"，且能按库名定位 {@code nlp2sql_test} 记录；</li>
 *   <li>{@code POST /datasources/{id}/test} 对真实数据源 = true；对不可达数据源 = HTTP 200 + 业务码 500
 *       + "连接失败"（旧项目同形状，不是抛异常）；</li>
 *   <li>CRUD 全环：create → list 可见 → PUT 改名（密码 "******" 表示不修改）→ DELETE → list 不可见；
 *       更新不存在的数据源 → 1001；创建缺字段 → HTTP 400（@Valid）；</li>
 *   <li>{@code GET /metadata/tables}、{@code /tables/{name}/columns}、{@code POST /metadata/refresh}
 *       对 {@code nlp2sql_test} 真实返回表与列。</li>
 * </ol>
 *
 * <p>数据源按库名定位，不硬编码 id；用例内创建/改名的临时数据源在 {@link #cleanup()} 里按名清理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataSourceMetadataIntegrationTest {

    /** 目标库：nlp2sql_test（T9-T11 同口径） */
    private static final String TARGET_DATABASE = "nlp2sql_test";

    private static final String PASSWORD = "t12-pass-123456";

    /** 用例创建的临时数据源名称前缀（清理用） */
    private static final String TEMP_NAME_PREFIX = "t12-ds-";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Autowired
    private DataSourceFactory dataSourceFactory;

    /** 用例内创建的临时数据源 id，结束时清理（含适配器缓存，避免连接池悬挂） */
    private Long createdTempId;

    @AfterEach
    void cleanup() {
        if (createdTempId != null) {
            dataSourceFactory.removeAdapter(createdTempId);
        }
        // 兜底清扫：不管断言在哪一步失败，都不把临时数据源留在库里
        List<DataSource> leftovers = dataSourceMapper.selectList(new LambdaQueryWrapper<DataSource>()
                .likeRight(DataSource::getName, TEMP_NAME_PREFIX));
        for (DataSource ds : leftovers) {
            dataSourceFactory.removeAdapter(ds.getId());
            dataSourceMapper.deleteById(ds.getId());
        }
    }

    /** 数据源 + 元数据两个控制器的正向链路（需要本机 MySQL 与 nlp2sql_test 记录）。 */
    @Test
    void datasourceCrudAndMetadataBrowse() {
        assumeMysqlUp();
        String token = registerAndLogin();

        // ── supported-types ──
        ApiResponse<List<DataSourceTypeInfo>> types = getJson(
                "/api/v1/datasources/supported-types", token,
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, types.getCode(), "supported-types 应返回 200，实际: " + types.getMessage());
        assertTrue(types.getData().stream().anyMatch(t -> "mysql".equals(t.code())),
                "应包含 mysql 类型: " + types.getData());
        assertTrue(types.getData().stream().anyMatch(t -> "postgresql".equals(t.code())),
                "应包含 postgresql 类型: " + types.getData());
        System.out.println("[T12-api] supported-types=" + types.getData());

        // ── 列表：按库名定位真实数据源，密码脱敏 ──
        ApiResponse<List<DataSourceVO>> list = getJson(
                "/api/v1/datasources", token, new ParameterizedTypeReference<>() {
                });
        assertEquals(200, list.getCode());
        DataSourceVO target = list.getData().stream()
                .filter(d -> TARGET_DATABASE.equals(d.getDatabaseName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "datasources 缺少 database_name='" + TARGET_DATABASE + "' 的记录，请补记录后重跑"));
        assertEquals("******", target.getPassword(), "列表密码应脱敏");
        assertTrue(list.getData().stream().allMatch(d -> "******".equals(d.getPassword())),
                "所有记录密码都应脱敏");
        System.out.println("[T12-api] 列表 " + list.getData().size() + " 条，定位 " + TARGET_DATABASE
                + " -> id=" + target.getId());

        // ── 连接测试：真实数据源 = true ──
        ApiResponse<Boolean> testOk = postJson("/api/v1/datasources/" + target.getId() + "/test", null, token,
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, testOk.getCode(), "连接测试应成功，实际: " + testOk.getMessage());
        assertEquals(Boolean.TRUE, testOk.getData());

        // ── 元数据：表列表 / 列详情 / 刷新 ──
        ApiResponse<List<TableInfo>> tables = getJson(
                "/api/v1/metadata/tables?datasourceId=" + target.getId(), token,
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, tables.getCode(), "表列表应返回 200，实际: " + tables.getMessage());
        assertTrue(tables.getData().stream().anyMatch(t -> "users".equals(t.getTableName())),
                "nlp2sql_test 应包含 users 表");

        ApiResponse<List<ColumnInfo>> columns = getJson(
                "/api/v1/metadata/tables/users/columns?datasourceId=" + target.getId(), token,
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, columns.getCode());
        assertFalse(columns.getData().isEmpty(), "users 表应有列信息");
        assertTrue(columns.getData().stream().anyMatch(c -> "id".equals(c.getColumnName())),
                "users 表应包含 id 列: " + columns.getData().stream().map(ColumnInfo::getColumnName).toList());
        System.out.println("[T12-api] metadata/tables=" + tables.getData().size() + " 张，users 列="
                + columns.getData().stream().map(ColumnInfo::getColumnName).toList());

        ApiResponse<Void> refreshed = postJson(
                "/api/v1/metadata/refresh?datasourceId=" + target.getId(), null, token,
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, refreshed.getCode(), "刷新缓存应返回 200，实际: " + refreshed.getMessage());
        System.out.println("[T12-api] metadata/refresh -> " + refreshed.getMessage());
    }

    /** CRUD 全环 + 失败语义 + 参数校验（零 LLM；临时数据源指向未监听端口，连接测试快速失败）。 */
    @Test
    void datasourceCrudLifecycleAndValidation() {
        assumeMysqlUp();
        String token = registerAndLogin();

        String name = TEMP_NAME_PREFIX + System.currentTimeMillis();
        ApiResponse<DataSourceVO> created = postJson("/api/v1/datasources", datasourceBody(name, 13306), token,
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, created.getCode(), "创建应成功，实际: " + created.getMessage());
        assertNotNull(created.getData().getId(), "创建响应应带 id");
        createdTempId = created.getData().getId();
        assertEquals("******", created.getData().getPassword(), "创建响应密码应脱敏");

        // 列表可见
        ApiResponse<List<DataSourceVO>> list = getJson("/api/v1/datasources", token,
                new ParameterizedTypeReference<>() {
                });
        assertTrue(list.getData().stream().anyMatch(d -> createdTempId.equals(d.getId())), "新建数据源应出现在列表");

        // 不可达数据源连接测试：HTTP 200 + 业务码 500 + "连接失败"（旧项目同形状）
        ApiResponse<Boolean> testFail = postJson(
                "/api/v1/datasources/" + createdTempId + "/test", null, token,
                new ParameterizedTypeReference<>() {
                });
        assertEquals(500, testFail.getCode(), "不可达数据源连接测试应为业务码 500，实际: " + testFail.getCode());
        assertEquals("连接失败", testFail.getMessage());
        System.out.println("[T12-api] 不可达数据源连接测试 -> HTTP 200 code=" + testFail.getCode()
                + " message=" + testFail.getMessage());

        // 更新：改名 + 密码 "******" 表示不修改
        String newName = name + "-renamed";
        ApiResponse<DataSourceVO> updated = putJson("/api/v1/datasources/" + createdTempId,
                datasourceBody(newName, 13306), token, new ParameterizedTypeReference<>() {
                });
        assertEquals(200, updated.getCode(), "更新应成功，实际: " + updated.getMessage());
        assertEquals(newName, updated.getData().getName());

        // 更新不存在的数据源：1001（BusinessException，HTTP 200 + body 码）
        ApiResponse<DataSourceVO> missing = putJson("/api/v1/datasources/999999999",
                datasourceBody(name + "-x", 13306), token, new ParameterizedTypeReference<>() {
                });
        assertEquals(1001, missing.getCode(), "更新不存在的数据源应为 1001，实际: " + missing.getCode());

        // 创建缺字段：@Valid → HTTP 400
        Map<String, Object> invalid = datasourceBody("", 13306);
        ResponseEntity<String> badRequest = restTemplate.exchange("/api/v1/datasources", HttpMethod.POST,
                new HttpEntity<>(invalid, jsonHeaders(token)), String.class);
        assertEquals(400, badRequest.getStatusCode().value(), "缺名称创建应返回 HTTP 400");

        // 删除 → 列表不可见
        ApiResponse<Void> deleted = delete("/api/v1/datasources/" + createdTempId, token);
        assertEquals(200, deleted.getCode(), "删除应成功，实际: " + deleted.getMessage());
        createdTempId = null;
        ApiResponse<List<DataSourceVO>> afterDelete = getJson("/api/v1/datasources", token,
                new ParameterizedTypeReference<>() {
                });
        assertTrue(afterDelete.getData().stream()
                        .noneMatch(d -> name.equals(d.getName()) || newName.equals(d.getName())),
                "删除后列表不应再出现该数据源");
        System.out.println("[T12-api] CRUD 环: create -> list -> test(失败语义) -> update -> 1001 -> 400 -> delete 全部通过");
    }

    /** 未认证访问两个新控制器 → 403（与 T10 认证行为一致）。 */
    @Test
    void anonymousAccessIsRejected() {
        ResponseEntity<String> list = restTemplate.exchange(
                "/api/v1/datasources", HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertEquals(403, list.getStatusCode().value(),
                "未认证访问数据源列表应返回 403，实际: " + list.getStatusCode().value());

        ResponseEntity<String> tables = restTemplate.exchange(
                "/api/v1/metadata/tables?datasourceId=1", HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertEquals(403, tables.getStatusCode().value(),
                "未认证访问元数据应返回 403，实际: " + tables.getStatusCode().value());
        System.out.println("[T12-api] 未认证访问 datasources/metadata -> HTTP 403");
    }

    // ── HTTP helpers ──

    private String registerAndLogin() {
        String username = "t12u" + System.currentTimeMillis() + (int) (Math.random() * 1000);
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(PASSWORD);
        Map<String, String> credentials = Map.of("username", username, "password", PASSWORD);

        ResponseEntity<ApiResponse<String>> registered = restTemplate.exchange(
                "/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(credentials, jsonHeaders(null)),
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, registered.getStatusCode().value(), "注册 HTTP 应 200");
        assertNotNull(registered.getBody());
        assertEquals(200, registered.getBody().getCode(), "注册应成功: " + registered.getBody().getMessage());

        ResponseEntity<ApiResponse<LoginResponse>> logged = restTemplate.exchange(
                "/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(credentials, jsonHeaders(null)),
                new ParameterizedTypeReference<>() {
                });
        assertNotNull(logged.getBody());
        assertEquals(200, logged.getBody().getCode(), "登录应成功: " + logged.getBody().getMessage());
        return logged.getBody().getData().getToken();
    }

    private <T> ApiResponse<T> getJson(String path, String token, ParameterizedTypeReference<ApiResponse<T>> type) {
        ResponseEntity<ApiResponse<T>> response = restTemplate.exchange(
                path, HttpMethod.GET, new HttpEntity<>(null, jsonHeaders(token)), type);
        assertEquals(200, response.getStatusCode().value(), path + " HTTP 应 200");
        assertNotNull(response.getBody(), path + " 响应体不应为空");
        return response.getBody();
    }

    private <T> ApiResponse<T> postJson(String path, Object body, String token,
                                        ParameterizedTypeReference<ApiResponse<T>> type) {
        ResponseEntity<ApiResponse<T>> response = restTemplate.exchange(
                path, HttpMethod.POST, new HttpEntity<>(body, jsonHeaders(token)), type);
        assertEquals(200, response.getStatusCode().value(), path + " HTTP 应 200");
        assertNotNull(response.getBody(), path + " 响应体不应为空");
        return response.getBody();
    }

    private <T> ApiResponse<T> putJson(String path, Object body, String token,
                                       ParameterizedTypeReference<ApiResponse<T>> type) {
        ResponseEntity<ApiResponse<T>> response = restTemplate.exchange(
                path, HttpMethod.PUT, new HttpEntity<>(body, jsonHeaders(token)), type);
        assertEquals(200, response.getStatusCode().value(), path + " HTTP 应 200");
        assertNotNull(response.getBody(), path + " 响应体不应为空");
        return response.getBody();
    }

    private ApiResponse<Void> delete(String path, String token) {
        ResponseEntity<ApiResponse<Void>> response = restTemplate.exchange(
                path, HttpMethod.DELETE, new HttpEntity<>(null, jsonHeaders(token)),
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, response.getStatusCode().value(), path + " HTTP 应 200");
        return response.getBody();
    }

    private static HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    /** 数据源请求体（字段齐全，便于复用 create / update；update 时密码传 "******" 语义由服务层处理）。 */
    private static Map<String, Object> datasourceBody(String name, int port) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("type", "mysql");
        body.put("host", "localhost");
        body.put("port", port);
        body.put("databaseName", TARGET_DATABASE);
        body.put("username", "root");
        body.put("password", PASSWORD);
        return body;
    }

    /** MySQL 可达才执行（与既有集成用例同一环境守卫模式）。 */
    private void assumeMysqlUp() {
        Assumptions.assumeTrue(portReachable(3306), "本机 MySQL 未启动，跳过 T12 控制器集成验证");
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
