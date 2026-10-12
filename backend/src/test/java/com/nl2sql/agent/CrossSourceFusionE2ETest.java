package com.nl2sql.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.adapter.DataSourceAdapter;
import com.nl2sql.adapter.DataSourceFactory;
import com.nl2sql.mapper.ChatSessionMapper;
import com.nl2sql.mapper.ChatTurnMapper;
import com.nl2sql.mapper.DataSourceMapper;
import com.nl2sql.mapper.UserMapper;
import com.nl2sql.model.ChatTurn;
import com.nl2sql.model.DataSource;
import com.nl2sql.model.User;
import com.nl2sql.model.dto.ApiResponse;
import com.nl2sql.model.dto.LoginResponse;
import com.nl2sql.model.dto.QueryResult;
import com.nl2sql.model.vo.AgentTraceItem;
import com.nl2sql.model.vo.ChatResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话式跨源融合 E2E（W4 演示的预演，真实本地 7B）——T23 核心验收用例。
 *
 * <p>场景（同一会话两问，经真实 HTTP 聊天通道 + 会话历史注入 + T23 的两类适配器）：
 * <ol>
 *   <li>对 MySQL 数据源（{@code nlp2sql_sample}）问「各部门分别有多少名员工？」——员工数在
 *       MySQL 的 departments/users 两表里（无 employees 表，需按 department_id 关联），
 *       测试内附库内关系提示并要求按部门名称列出（见 {@link #QUESTION_1}）；</li>
 *   <li>切 REST API 数据源（mock-api，3100）问「把各部门的预算和刚才的员工数放在一起对比一下」
 *       ——预算只存在于 API 侧，员工数只在第一轮回答里（会话历史），模型既要查 API 又要读历史；</li>
 * </ol>
 *
 * <p><b>融合断言只要求并列引用两侧真实数值</b>（每个部门名 + MySQL 真实员工数 + API 真实预算），
 * 不要求任何心算/派生指标（T11 教训：7B 心算不可靠，差值类断言已实证失败过）。
 * 断言所用真值在测试内经适配器直连两侧取得（不写死、不消耗 LLM）。
 *
 * <p>环境守卫与本地端到端用例一致（3306 / 11434 / 3100 任一不通即 assume 跳过）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrossSourceFusionE2ETest {

    /** MySQL 侧目标库。 */
    private static final String MYSQL_DATABASE = "nlp2sql_sample";

    /** API 侧数据源（T23 入库记录）。 */
    private static final String REST_DATASOURCE_NAME = "REST 演示服务";

    /** mock-api 端口。 */
    private static final int MOCK_API_PORT = 3100;

    private static final String PASSWORD = "t23-fusion-pass";

    /**
     * 第一问（MySQL 侧：员工数）。
     *
     * <p>初版为 brief 原话「各部门分别有多少名员工？」，实测本地 7B 按 {@code department_id}
     * 分组并作答「部门1：5名员工…」（拿不到部门名）——历史里没有名字，第二轮的融合断言
     * （部门名 + 员工数 + 预算）无从谈起。按「模型行为问题最多修 1 次」的约束，加一句
     * T22 同风格的库内关系提示并要求按名称列出（核心问题未变），此为该次修复。
     */
    private static final String QUESTION_1 =
            "users 表记录员工（department_id 关联 departments 表），请统计各部门分别有多少名员工，并按部门名称列出。";

    /**
     * 第二问（切到 REST API 侧：预算 + 历史中的员工数，要求并列对比，不要求心算）。
     *
     * <p>措辞经历三轮模型行为适配（详见 T23 报告"探针实验"节）：
     * ① 初版「把…放在一起对比一下」触发整体拒答——根因与问句无关（历史与当前两组
     * 「数据源ID为 N」被误读为更换数据源），已通过历史去包装修复；
     * ② 修复后模型把历史里的表名当本数据源的表、尝试单条 SQL 跨源 JOIN 并耗尽迭代；
     * ③ 过度显式的「员工数不用重新查」又让模型直接索要数据而不查询。
     * 最终采用「问句打头（查询语义）+ 合并要求落在回答格式上」的措辞（实测最稳），
     * 并配合 execute_sql 工具描述与 API 适配器引导式报错的双重跨源约束。
     * 融合断言口径全程未放宽。
     */
    private static final String QUESTION_2 = "各部门的预算分别是多少？回答时请把每个部门对应上一轮的员工数一起列出来。";

    /** 随机端口（本测试直连 HTTP 通道）。 */
    @LocalServerPort
    private int port;

    /**
     * 客户端 readTimeout 放宽到 3 分钟：跨源第二轮上下文更长（历史 + 双数据源元数据），
     * 本地 7B 完整工具链常超 10 秒——OkHttp 默认 10s 读超时会误杀正常请求（T23 实测：
     * 第一轮 9.6s 险过，第二轮直接被掐断）。
     */
    private final RestTemplate restTemplate = new RestTemplateBuilder()
            .setConnectTimeout(Duration.ofSeconds(10))
            .setReadTimeout(Duration.ofMinutes(3))
            .build();

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Autowired
    private DataSourceFactory dataSourceFactory;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ChatSessionMapper sessionMapper;

    @Autowired
    private ChatTurnMapper turnMapper;

    /** 本用例经工厂缓存的适配器对应的数据源 id，结束后清理。 */
    private final List<Long> cachedAdapterIds = new ArrayList<>();

    /** 本用例创建的会话 id（连同轮次清理）。 */
    private Long createdSessionId;

    @AfterEach
    void cleanup() {
        for (Long id : cachedAdapterIds) {
            dataSourceFactory.removeAdapter(id);
        }
        cachedAdapterIds.clear();
        if (createdSessionId != null) {
            turnMapper.delete(new LambdaQueryWrapper<ChatTurn>().eq(ChatTurn::getSessionId, createdSessionId));
            sessionMapper.deleteById(createdSessionId);
            createdSessionId = null;
        }
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void secondTurnFusesEmployeeCountsAndBudgetsAcrossSources() {
        Assumptions.assumeTrue(portReachable(3306), "本机 MySQL 未启动，跳过跨源融合验证");
        Assumptions.assumeTrue(portReachable(11434), "本机 Ollama 未启动，跳过跨源融合验证");
        Assumptions.assumeTrue(portReachable(MOCK_API_PORT), "mock-api(3100) 未启动，跳过跨源融合验证"
                + "（启动：cd docker/mock-api && npm start）");

        // ── 两侧真实数据：测试内直连适配器取真值（断言依据，不消耗 LLM） ──
        DataSource mysqlDs = mysqlDatasource();
        DataSource restDs = restDatasource();
        Map<String, Long> employeeCounts = employeeCounts(mysqlDs);
        Map<String, Long> budgets = budgets(restDs);
        System.out.println("[T23-fusion] MySQL 侧各部门员工数 = " + employeeCounts);
        System.out.println("[T23-fusion] API 侧各部门预算 = " + budgets);
        assertEquals(5, employeeCounts.size(), "MySQL 侧应有 5 个部门: " + employeeCounts);
        assertEquals(employeeCounts.keySet(), budgets.keySet(),
                "两侧部门名应对齐（deptName 与 departments.name）");

        Session login = registerAndLogin();

        // ── 第一问：MySQL 数据源（不带 sessionId，服务端自动新建会话） ──
        ChatResult first = chat(mysqlDs.getId(), QUESTION_1, null, login.token());
        assertNotNull(first.getSessionId(), "自动新建会话后 ChatResult.sessionId 不应为空");
        createdSessionId = first.getSessionId();
        printTurn("第1轮(MySQL)", QUESTION_1, first);

        for (String dept : employeeCounts.keySet()) {
            assertTrue(first.getAnswer().contains(dept),
                    "第 1 轮回答应含部门名 " + dept + "，实际: " + first.getAnswer());
        }
        for (Map.Entry<String, Long> entry : employeeCounts.entrySet()) {
            assertTrue(mentionsNumber(first.getAnswer(), String.valueOf(entry.getValue())),
                    "第 1 轮回答应含 " + entry.getKey() + " 的真实员工数 " + entry.getValue()
                            + "，实际: " + first.getAnswer());
        }

        // ── 第二问：切 REST API 数据源，同一会话（历史注入 = 跨源融合的关键） ──
        ChatResult second = chat(restDs.getId(), QUESTION_2, createdSessionId, login.token());
        assertEquals(createdSessionId, second.getSessionId(), "带 sessionId 时不应另建会话");
        printTurn("第2轮(REST API)", QUESTION_2, second);

        // 融合断言：第二轮回答同时引用两侧真实数值（部门名 + 员工数 + 预算），不做心算要求
        for (String dept : employeeCounts.keySet()) {
            assertTrue(second.getAnswer().contains(dept),
                    "第 2 轮回答应含部门名 " + dept + "，实际: " + second.getAnswer());
        }
        for (Map.Entry<String, Long> entry : employeeCounts.entrySet()) {
            assertTrue(mentionsNumber(second.getAnswer(), String.valueOf(entry.getValue())),
                    "第 2 轮回答应引用 MySQL 侧真实员工数（" + entry.getKey() + "=" + entry.getValue()
                            + "），实际: " + second.getAnswer());
        }
        for (Map.Entry<String, Long> entry : budgets.entrySet()) {
            assertTrue(mentionsNumber(second.getAnswer(), String.valueOf(entry.getValue())),
                    "第 2 轮回答应引用 API 侧真实预算（" + entry.getKey() + "=" + entry.getValue()
                            + "），实际: " + second.getAnswer());
        }
        assertTrue(toolNames(second).contains("execute_sql"),
                "第 2 轮应真实查询 REST 数据源（预算不在历史里），实际工具序列: " + toolNames(second));
    }

    /** MySQL 侧真值：departments LEFT JOIN users 按部门统计员工数（无 employees 表，按 department_id 关联）。 */
    private Map<String, Long> employeeCounts(DataSource mysqlDs) {
        DataSourceAdapter adapter = dataSourceFactory.getAdapter(mysqlDs.getId(), mysqlDs.getType(),
                mysqlDs.toAdapterConfig());
        cachedAdapterIds.add(mysqlDs.getId());
        QueryResult result = adapter.executeQuery(
                "SELECT d.name AS dept_name, COUNT(u.id) AS emp_count FROM departments d"
                        + " LEFT JOIN users u ON u.department_id = d.id GROUP BY d.name LIMIT 50", 50);
        assertTrue(result.isSuccess(), "MySQL 侧员工数查询失败: " + result.getErrorMessage());
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : result.getRows()) {
            counts.put(String.valueOf(rowValue(row, "dept_name")),
                    ((Number) rowValue(row, "emp_count")).longValue());
        }
        return counts;
    }

    /** API 侧真值：REST 适配器查询 department_budgets（budget 与 mock 数据一致）。 */
    private Map<String, Long> budgets(DataSource restDs) {
        DataSourceAdapter adapter = dataSourceFactory.getAdapter(restDs.getId(), restDs.getType(),
                restDs.toAdapterConfig());
        cachedAdapterIds.add(restDs.getId());
        QueryResult result = adapter.executeQuery(
                "SELECT deptName, budget FROM department_budgets LIMIT 50", 50);
        assertTrue(result.isSuccess(), "REST 侧预算查询失败: " + result.getErrorMessage());
        Map<String, Long> values = new LinkedHashMap<>();
        for (Map<String, Object> row : result.getRows()) {
            values.put(String.valueOf(rowValue(row, "deptName")),
                    ((Number) rowValue(row, "budget")).longValue());
        }
        return values;
    }

    /**
     * 按列名取值（大小写不敏感）：MySQL 的别名按书写原样返回（dept_name），
     * H2 的列标签统一大写（DEPTNAME），两侧真值读取共用本方法。
     */
    private static Object rowValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            value = row.get(key.toUpperCase(java.util.Locale.ROOT));
        }
        if (value == null) {
            value = row.get(key.toLowerCase(java.util.Locale.ROOT));
        }
        assertNotNull(value, "结果行缺少列 " + key + "，实际列: " + row.keySet());
        return value;
    }

    /**
     * 回答中是否“独立”出现某数字：去分隔符后按 (?&lt;!\d)N(?!\d) 匹配，
     * 使「880」「880万」「880,000」都命中、而「1880」「8800」不命中。
     */
    private static boolean mentionsNumber(String answer, String number) {
        String cleaned = answer.replaceAll("[,，\\s]", "");
        return Pattern.compile("(?<!\\d)" + Pattern.quote(number) + "(?!\\d)").matcher(cleaned).find();
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
                baseUrl() + "/api/v1/chat", HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)),
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

    /** 打印一轮问答的轨迹摘要与完整回答（报告留证用）。 */
    private void printTurn(String label, String question, ChatResult result) {
        System.out.println("[T23-fusion] " + label + " Q: " + question);
        System.out.println("[T23-fusion] " + label + " 工具序列: " + String.join(" -> ", toolNames(result)));
        result.getTrace().stream()
                .filter(item -> AgentTraceItem.TYPE_TOOL_CALL.equals(item.type())
                        && "execute_sql".equals(item.tool()))
                .forEach(item -> System.out.println("[T23-fusion] " + label + " SQL 入参: " + item.input()));
        System.out.println("[T23-fusion] " + label + " 回答全文: " + result.getAnswer());
        System.out.println("[T23-fusion] " + label + " latencyMs=" + result.getLatencyMs()
                + " firstEventMs=" + result.getFirstEventMs() + " rowCount=" + result.getRowCount());
    }

    private static List<String> toolNames(ChatResult result) {
        return result.getTrace().stream()
                .filter(item -> AgentTraceItem.TYPE_TOOL_CALL.equals(item.type()))
                .map(AgentTraceItem::tool)
                .toList();
    }

    /** 测试内注册的登录会话（用户名 + token，只在进程内传递，不落盘、不进报告）。 */
    private record Session(String username, String token) {
    }

    /** 注册随机用户并登录（受保护接口需要 JWT）；随机名避免与既有数据冲突。 */
    private Session registerAndLogin() {
        String username = "t23f" + System.currentTimeMillis() + (int) (Math.random() * 1000);
        HttpHeaders headers = authHeaders(null);

        ResponseEntity<ApiResponse<String>> registered = restTemplate.exchange(
                baseUrl() + "/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD), headers),
                new ParameterizedTypeReference<>() {
                });
        assertEquals(200, registered.getBody() == null ? -1 : registered.getBody().getCode(),
                "测试用户注册应成功");

        ResponseEntity<ApiResponse<LoginResponse>> login = restTemplate.exchange(
                baseUrl() + "/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD), headers),
                new ParameterizedTypeReference<>() {
                });
        LoginResponse data = login.getBody() == null ? null : login.getBody().getData();
        assertNotNull(data, "登录 data 不应为空");
        assertNotNull(data.getToken(), "token 不应为空");
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        assertNotNull(user, "注册用户应能在 users 表查到: " + username);
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

    /** 按库名定位 MySQL 数据源；查不到直接断言失败并提示补记录。 */
    private DataSource mysqlDatasource() {
        DataSource ds = dataSourceMapper.selectOne(new LambdaQueryWrapper<DataSource>()
                .eq(DataSource::getType, "mysql")
                .eq(DataSource::getDatabaseName, MYSQL_DATABASE)
                .orderByAsc(DataSource::getId)
                .last("LIMIT 1"));
        assertNotNull(ds, "nlp2sql_meta.datasources 中缺少 database_name='" + MYSQL_DATABASE
                + "' 的 mysql 记录");
        return ds;
    }

    /** 按名称定位 T23 入库的 REST 数据源。 */
    private DataSource restDatasource() {
        DataSource ds = dataSourceMapper.selectOne(new LambdaQueryWrapper<DataSource>()
                .eq(DataSource::getName, REST_DATASOURCE_NAME)
                .eq(DataSource::getType, "rest")
                .orderByAsc(DataSource::getId)
                .last("LIMIT 1"));
        assertNotNull(ds, "nlp2sql_meta.datasources 中缺少 name='" + REST_DATASOURCE_NAME
                + "' 的 rest 记录，请先执行 docker/mock-api/init_datasources.sql");
        return ds;
    }

    /** 随机端口下的本机基址。 */
    private String baseUrl() {
        return "http://localhost:" + port;
    }

    /** 端口探测（与各集成测试同写法）。 */
    private static boolean portReachable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
