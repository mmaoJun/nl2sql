package com.nl2sql.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.adapter.api.GraphQLAdapter;
import com.nl2sql.adapter.api.RestApiAdapter;
import com.nl2sql.mapper.ChatSessionMapper;
import com.nl2sql.mapper.ChatTurnMapper;
import com.nl2sql.mapper.DataSourceMapper;
import com.nl2sql.model.ChatSession;
import com.nl2sql.model.ChatTurn;
import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.DataSource;
import com.nl2sql.model.TableInfo;
import com.nl2sql.model.dto.QueryResult;
import com.nl2sql.model.dto.SessionCreateRequest;
import com.nl2sql.model.vo.SessionVO;
import com.nl2sql.service.ChatSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REST / GraphQL API 适配器集成验证（T23）。
 *
 * <p>目标服务为本地 mock-api（docker/mock-api，端口 3100，X-API-KEY 鉴权），
 * 数据与 nlp2sql_sample.departments 对齐。用例：
 * <ol>
 *   <li>{@link #factoryRegistersRestAndGraphqlTypeMetadata()}——类型注册元数据
 *       （code/displayName/defaultPort/方言），不依赖外部服务；</li>
 *   <li>{@link #restAdapterReadsMetadataAndAggregation()}——REST 适配器：表清单/列推断
 *       （含类型）/聚合查询/跨协议一致性基准；</li>
 *   <li>{@link #graphqlAdapterReadsMetadataAndAggregation()}——GraphQL 适配器同上，
 *       并与 REST 侧数值逐项一致（同一份 mock 数据、两条协议路径）；</li>
 *   <li>{@link #missingApiKeyIsRejectedWith401Message()}——鉴权：缺 X-API-KEY 时
 *       两个适配器的查询都被拒（401），错误消息含修复提示；</li>
 *   <li>{@link #sessionHistoryCrossesDatasourcesForFusion()}——T23 跨源历史取数
 *       （recentSessionTurns 跨数据源、recentTurns 的数据源隔离语义未被破坏），零 LLM。</li>
 * </ol>
 *
 * <p>守卫写法与 Hive/HDFS 集成测试一致：3100 不通（mock-api 未启动）时 assume 跳过，
 * 不阻塞无 mock 环境的构建。启动命令见 docker/mock-api/README.md。
 */
@SpringBootTest
class ApiAdapterIntegrationTest {

    /** mock 服务端口（docker/mock-api）。 */
    private static final int MOCK_API_PORT = 3100;

    /** T23 入库的数据源名称（nlp2sql_meta.datasources）。 */
    private static final String REST_DATASOURCE_NAME = "REST 演示服务";
    private static final String GRAPHQL_DATASOURCE_NAME = "GraphQL 演示服务";

    /** 表声明名（extra_config.tables[0].name）。 */
    private static final String TABLE = "department_budgets";

    /** mock 数据（与 server.js / README 对齐）：部门名 → budget（万元）。 */
    private static final Map<String, Long> MOCK_BUDGETS = new LinkedHashMap<>();

    static {
        MOCK_BUDGETS.put("技术部", 880L);
        MOCK_BUDGETS.put("市场部", 460L);
        MOCK_BUDGETS.put("财务部", 520L);
        MOCK_BUDGETS.put("人事部", 240L);
        MOCK_BUDGETS.put("运营部", 310L);
    }

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Autowired
    private DataSourceFactory dataSourceFactory;

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private ChatSessionMapper sessionMapper;

    @Autowired
    private ChatTurnMapper turnMapper;

    /** 本用例内经工厂缓存的适配器对应的数据源 id，结束时清理。 */
    private final List<Long> cachedAdapterIds = new ArrayList<>();

    /** 本用例创建的会话 id（跨源历史用例），结束后清理。 */
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
    void factoryRegistersRestAndGraphqlTypeMetadata() {
        assertTrue(dataSourceFactory.isSupported("rest"), "rest 类型应已注册");
        assertTrue(dataSourceFactory.isSupported("graphql"), "graphql 类型应已注册");
        // SQL 实际由 H2 内存表执行，方言上报 h2
        assertEquals("h2", dataSourceFactory.getDialect("rest"));
        assertEquals("h2", dataSourceFactory.getDialect("graphql"));
        assertInstanceOf(RestApiAdapter.class, dataSourceFactory.createAdapter("rest"));
        assertInstanceOf(GraphQLAdapter.class, dataSourceFactory.createAdapter("graphql"));

        DataSourceTypeInfo rest = dataSourceFactory.supportedTypes().stream()
                .filter(t -> "rest".equals(t.code())).findFirst().orElseThrow();
        DataSourceTypeInfo graphql = dataSourceFactory.supportedTypes().stream()
                .filter(t -> "graphql".equals(t.code())).findFirst().orElseThrow();
        assertEquals("REST API", rest.displayName());
        assertEquals(3100, rest.defaultPort());
        assertEquals("GraphQL API", graphql.displayName());
        assertEquals(3100, graphql.defaultPort());
    }

    @Test
    void restAdapterReadsMetadataAndAggregation() {
        Assumptions.assumeTrue(portReachable(MOCK_API_PORT), "mock-api(3100) 未启动，跳过 REST 适配器集成验证");
        DataSource ds = apiDatasource(REST_DATASOURCE_NAME, "rest");
        DataSourceAdapter adapter = dataSourceFactory.getAdapter(ds.getId(), ds.getType(), ds.toAdapterConfig());
        cachedAdapterIds.add(ds.getId());

        assertEquals("h2", adapter.getDialect());
        assertTrue(dataSourceFactory.testConnection("rest", ds.toAdapterConfig()), "工厂连接测试应通过");

        // getTables：config 声明的表 + 注释（取数方式）
        List<TableInfo> tables = adapter.getTables();
        System.out.println("[T23-rest] getTables() = " + tables.stream()
                .map(t -> t.getTableName() + " (" + t.getComment() + ")").toList());
        assertEquals(1, tables.size(), "应恰好声明 1 张表");
        assertEquals(TABLE, tables.get(0).getTableName());

        // getColumns：按真实响应采样推断——deptId/budget 为整数（BIGINT），deptName 字符串（VARCHAR）
        List<ColumnInfo> columns = adapter.getColumns(TABLE);
        Map<String, String> columnTypes = new LinkedHashMap<>();
        columns.forEach(c -> columnTypes.put(c.getColumnName(), c.getDataType()));
        System.out.println("[T23-rest] getColumns = " + columnTypes);
        assertEquals(List.of("deptId", "deptName", "budget"),
                columns.stream().map(ColumnInfo::getColumnName).toList());
        assertEquals("BIGINT", columnTypes.get("deptId"));
        assertEquals("VARCHAR", columnTypes.get("deptName"));
        assertEquals("BIGINT", columnTypes.get("budget"));

        // 行集：5 个部门与 mock 数据逐项一致（中文经 HTTP → JSON → H2 往返）
        Map<String, Long> actual = queryBudgets(adapter);
        System.out.println("[T23-rest] department_budgets rows = " + actual);
        assertEquals(MOCK_BUDGETS, actual, "REST 行集应与 mock 数据一致");

        // 聚合可用（数值列 SUM/COUNT 由 H2 执行）
        QueryResult sum = adapter.executeQuery(
                "SELECT COUNT(*) AS cnt, SUM(budget) AS total_budget FROM " + TABLE + " LIMIT 10", 10);
        assertTrue(sum.isSuccess(), "SUM 聚合失败: " + sum.getErrorMessage());
        System.out.println("[T23-rest] SUM/COUNT = " + sum.getRows());
        assertEquals(5L, ((Number) sum.getRows().get(0).get("CNT")).longValue());
        assertEquals(2410L, ((Number) sum.getRows().get(0).get("TOTAL_BUDGET")).longValue());

        // LIMIT 兜底
        QueryResult limited = adapter.executeQuery("SELECT * FROM " + TABLE, 2);
        assertTrue(limited.isSuccess(), "LIMIT 查询失败: " + limited.getErrorMessage());
        assertEquals(2, limited.getRowCount(), "5 行数据应被 LIMIT 截到 2");
    }

    @Test
    void graphqlAdapterReadsMetadataAndAggregation() {
        Assumptions.assumeTrue(portReachable(MOCK_API_PORT), "mock-api(3100) 未启动，跳过 GraphQL 适配器集成验证");
        DataSource ds = apiDatasource(GRAPHQL_DATASOURCE_NAME, "graphql");
        DataSourceAdapter adapter = dataSourceFactory.getAdapter(ds.getId(), ds.getType(), ds.toAdapterConfig());
        cachedAdapterIds.add(ds.getId());

        assertEquals("h2", adapter.getDialect());
        assertTrue(dataSourceFactory.testConnection("graphql", ds.toAdapterConfig()), "工厂连接测试应通过");

        List<TableInfo> tables = adapter.getTables();
        System.out.println("[T23-graphql] getTables() = " + tables.stream()
                .map(t -> t.getTableName() + " (" + t.getComment() + ")").toList());
        assertEquals(1, tables.size());
        assertEquals(TABLE, tables.get(0).getTableName());

        List<ColumnInfo> columns = adapter.getColumns(TABLE);
        System.out.println("[T23-graphql] getColumns = " + columns.stream()
                .map(c -> c.getColumnName() + ":" + c.getDataType()).toList());
        assertEquals(List.of("deptId", "deptName", "budget"),
                columns.stream().map(ColumnInfo::getColumnName).toList());

        // 与 REST 同数据、同结果（两条协议路径的一致性）
        Map<String, Long> actual = queryBudgets(adapter);
        System.out.println("[T23-graphql] departmentBudgets rows = " + actual);
        assertEquals(MOCK_BUDGETS, actual, "GraphQL 行集应与 REST 侧逐项一致");
    }

    @Test
    void missingApiKeyIsRejectedWith401Message() {
        Assumptions.assumeTrue(portReachable(MOCK_API_PORT), "mock-api(3100) 未启动，跳过鉴权用例");
        DataSource ds = apiDatasource(REST_DATASOURCE_NAME, "rest");
        Map<String, Object> withoutHeaders = new HashMap<>(ds.toAdapterConfig());
        withoutHeaders.remove("headers");

        // 连接探测走 /health（mock 对 health 不鉴权）：可达即 true，鉴权问题在业务请求上显形
        assertTrue(dataSourceFactory.testConnection("rest", withoutHeaders), "health 探测应可达");

        RestApiAdapter adapter = new RestApiAdapter();
        assertTrue(adapter.connect(withoutHeaders), "connect 走 /health 应成功");
        QueryResult denied = adapter.executeQuery("SELECT * FROM " + TABLE + " LIMIT 10", 10);
        assertFalse(denied.isSuccess(), "缺少 X-API-KEY 时查询应被拒");
        System.out.println("[T23-auth] 缺 X-API-KEY -> " + denied.getErrorMessage());
        assertTrue(denied.getErrorMessage().contains("401"), "错误消息应含 401: " + denied.getErrorMessage());
        assertTrue(denied.getErrorMessage().contains("X-API-KEY"), "错误消息应给出修复提示: " + denied.getErrorMessage());
        assertTrue(adapter.getColumns(TABLE).isEmpty(), "鉴权失败时列采样应返回空列表（不炸）");
        adapter.disconnect();

        GraphQLAdapter graphql = new GraphQLAdapter();
        Map<String, Object> graphqlWithoutHeaders = new HashMap<>(apiDatasource(GRAPHQL_DATASOURCE_NAME, "graphql")
                .toAdapterConfig());
        graphqlWithoutHeaders.remove("headers");
        assertTrue(graphql.connect(graphqlWithoutHeaders), "GraphQL connect 走 /health 应成功");
        QueryResult graphqlDenied = graphql.executeQuery("SELECT * FROM " + TABLE + " LIMIT 10", 10);
        assertFalse(graphqlDenied.isSuccess(), "GraphQL 缺 X-API-KEY 时查询应被拒");
        System.out.println("[T23-auth] graphql 缺 X-API-KEY -> " + graphqlDenied.getErrorMessage());
        assertTrue(graphqlDenied.getErrorMessage().contains("401"));
        graphql.disconnect();
    }

    @Test
    void sessionHistoryCrossesDatasourcesForFusion() {
        Assumptions.assumeTrue(portReachable(3306), "本机 MySQL 未启动，跳过跨源历史用例");
        long userId = 900_000_000_101L;
        long datasourceA = 900_101L;
        long datasourceB = 900_102L;

        SessionVO session = sessionService.create(userId, new SessionCreateRequest());
        createdSessionId = session.getId();
        Long first = insertTurn(session.getId(), datasourceA, "各部门分别有多少名员工？", "技术部5名。");
        Long second = insertTurn(session.getId(), datasourceB, "各部门的预算分别是多少？", "技术部880万元。");

        // T23 跨源历史：同会话所有数据源的轮次都取得到（时间正序）
        List<ChatTurn> crossSource = sessionService.recentSessionTurns(session.getId(), 3);
        assertEquals(List.of(first, second), crossSource.stream().map(ChatTurn::getId).toList(),
                "跨源历史应按时间正序返回两侧轮次");
        assertEquals(List.of(datasourceA, datasourceB),
                crossSource.stream().map(ChatTurn::getDatasourceId).toList());
        System.out.println("[T23-fusion] recentSessionTurns 跨源 ok: " + crossSource.stream()
                .map(t -> "turn#" + t.getId() + "@ds" + t.getDatasourceId()).toList());

        // T11 的按数据源隔离语义未被破坏（recentTurns 原样保留）
        assertEquals(1, sessionService.recentTurns(session.getId(), datasourceA, 3).size(),
                "recentTurns 仍应只返回同数据源轮次");
    }

    /** 经适配器查出「部门名 → budget」映射（跨协议一致性断言共用）。 */
    private Map<String, Long> queryBudgets(DataSourceAdapter adapter) {
        QueryResult result = adapter.executeQuery(
                "SELECT deptName, budget FROM " + TABLE + " LIMIT 50", 50);
        assertTrue(result.isSuccess(), "查询 department_budgets 失败: " + result.getErrorMessage());
        Map<String, Long> budgets = new LinkedHashMap<>();
        for (Map<String, Object> row : result.getRows()) {
            budgets.put(String.valueOf(row.get("DEPTNAME")), ((Number) row.get("BUDGET")).longValue());
        }
        return budgets;
    }

    /** 按名称+类型定位 T23 入库的 API 数据源；查不到直接断言失败并提示补记录。 */
    private DataSource apiDatasource(String name, String type) {
        DataSource ds = dataSourceMapper.selectOne(new LambdaQueryWrapper<DataSource>()
                .eq(DataSource::getName, name)
                .eq(DataSource::getType, type)
                .orderByAsc(DataSource::getId)
                .last("LIMIT 1"));
        assertNotNull(ds, "nlp2sql_meta.datasources 中缺少 name='" + name + "' type='" + type
                + "' 的记录，请先执行 docker/mock-api/init_datasources.sql（或经数据源管理接口创建）");
        return ds;
    }

    private Long insertTurn(Long sessionId, Long datasourceId, String question, String answer) {
        ChatTurn turn = new ChatTurn();
        turn.setSessionId(sessionId);
        turn.setDatasourceId(datasourceId);
        turn.setQuestion(question);
        turn.setModelName("qwen2.5-coder:7b");
        turn.setModelProvider("ollama");
        turn.setGeneratedSql("SELECT 1 LIMIT 1");
        turn.setRowCount(1);
        turn.setExecutionTimeMs(10L);
        turn.setStatus("ok");
        turn.setAnswer(answer);
        return sessionService.insertTurn(turn);
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
