package com.nl2sql.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.adapter.hdfs.HdfsFileAdapter;
import com.nl2sql.mapper.DataSourceMapper;
import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.DataSource;
import com.nl2sql.model.TableInfo;
import com.nl2sql.model.dto.QueryResult;
import com.nl2sql.service.DataSourceService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HdfsFileAdapter 连接本机 HDFS（docker/hive-hdfs 的 WebHDFS 9870，根目录 /demo）集成验证。
 *
 * <p>三段（与 T22 派发说明对应）：
 * <ol>
 *   <li>{@link #factoryRegistersHdfsTypeMetadata()}——类型注册元数据（code/displayName/defaultPort），
 *       不依赖任何外部服务；</li>
 *   <li>{@link #hdfsAdapterReadsCsvMetadataAndGroupByQuery()}——适配器路径（不经 LLM）：
 *       经 {@link DataSourceFactory} 用新入库数据源（name='HDFS 演示目录'，非硬编码 id）
 *       取适配器，断言 CSV 元数据（含子目录递归发现）与分组查询的真实返回，
 *       以中文城市名验证 UTF-8 往返；</li>
 *   <li>{@link #hdfsAgentAnswersOrdersPerCity()}——Agent 端到端 1 例（真实本地 7B，
 *       1 次问答预算）：直接装配默认 ReActAgent 提问，断言回答含真实数据痕迹。</li>
 * </ol>
 *
 * <p>守卫写法与 {@code HiveAdapterIntegrationTest} 一致：WebHDFS(9870) 等端口不达则
 * skip 而非 fail，保证无环境构建通过。
 */
@SpringBootTest
class HdfsFileAdapterIntegrationTest {

    /** 演示数据源在 nlp2sql_meta.datasources 中的名称（T22 入库记录）。 */
    private static final String HDFS_DATASOURCE_NAME = "HDFS 演示目录";

    /** demo 数据固定分布：orders 200 行，5 个中文城市各 40。 */
    private static final Set<String> CITIES = Set.of("北京", "上海", "广州", "深圳", "杭州");

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Autowired
    private DataSourceFactory dataSourceFactory;

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private ReActAgent nl2sqlAgent;

    /** 本用例内经工厂缓存的适配器对应的数据源 id，结束时清理。 */
    private Long cachedAdapterId;

    @AfterEach
    void cleanup() {
        if (cachedAdapterId != null) {
            dataSourceFactory.removeAdapter(cachedAdapterId);
        }
    }

    @Test
    void factoryRegistersHdfsTypeMetadata() {
        assertTrue(dataSourceFactory.isSupported("hdfs"), "hdfs 类型应已注册");
        // SQL 实际由 H2 执行，方言上报 h2
        assertEquals("h2", dataSourceFactory.getDialect("hdfs"));
        assertInstanceOf(HdfsFileAdapter.class, dataSourceFactory.createAdapter("hdfs"));

        DataSourceTypeInfo hdfs = dataSourceFactory.supportedTypes().stream()
                .filter(t -> "hdfs".equals(t.code()))
                .findFirst()
                .orElseThrow();
        assertEquals("HDFS 文件", hdfs.displayName());
        assertEquals(9870, hdfs.defaultPort());
    }

    @Test
    void hdfsAdapterReadsCsvMetadataAndGroupByQuery() {
        Assumptions.assumeTrue(portReachable(9870), "本机 WebHDFS 未启动，跳过 HDFS 适配器集成验证");
        DataSource ds = hdfsDatasource();

        // 经注册表用 datasource id 取适配器（与业务链路同一条路径）
        DataSourceAdapter adapter = dataSourceFactory.getAdapter(ds.getId(), ds.getType(), ds.toAdapterConfig());
        cachedAdapterId = ds.getId();
        assertEquals("h2", adapter.getDialect());

        // getTables：/demo 下递归发现的 CSV（实际位于 /demo/orders、/demo/products 子目录）
        List<TableInfo> tables = adapter.getTables();
        Set<String> tableNames = tables.stream().map(TableInfo::getTableName).collect(Collectors.toSet());
        System.out.println("[T22-hdfs] getTables() = " + tableNames);
        assertFalse(tables.isEmpty(), "根目录 /demo 下应有 CSV 表");
        assertTrue(tableNames.contains("hive_orders"), "应含 hive_orders，实际: " + tableNames);
        assertTrue(tableNames.contains("hive_products"), "应含 hive_products，实际: " + tableNames);

        // getColumns(hive_orders)：7 列，含 city（中文列值所在列）
        List<ColumnInfo> columns = adapter.getColumns("hive_orders");
        List<String> columnNames = columns.stream().map(ColumnInfo::getColumnName).toList();
        System.out.println("[T22-hdfs] getColumns(hive_orders) = " + columnNames);
        assertEquals(7, columns.size(), "hive_orders 应恰好 7 列，实际: " + columnNames);
        assertTrue(columnNames.contains("city"), "应含 city 列，实际: " + columnNames);
        assertTrue(columnNames.contains("amount"), "应含 amount 列，实际: " + columnNames);

        // 分组查询：5 个中文城市各 40 条（中文断言验证 UTF-8 编码往返）
        QueryResult grouped = adapter.executeQuery(
                "SELECT city, COUNT(*) AS cnt FROM hive_orders GROUP BY city", 100);
        assertTrue(grouped.isSuccess(), "分组查询失败: " + grouped.getErrorMessage());
        System.out.println("[T22-hdfs] columns=" + grouped.getColumns() + " rows=" + grouped.getRows());
        assertEquals(5, grouped.getRowCount(), "应恰好 5 个城市，实际: " + grouped.getRows());
        Set<String> actualCities = new java.util.HashSet<>();
        for (Map<String, Object> row : grouped.getRows()) {
            actualCities.add(String.valueOf(row.get("CITY")));
            assertEquals(40L, ((Number) row.get("CNT")).longValue(),
                    "每个城市应恰好 40 条，实际行: " + row);
        }
        assertEquals(CITIES, actualCities, "城市名应与演示数据完全一致（中文编码往返）");

        // LIMIT 兜底：200 行数据取 10 行
        QueryResult limited = adapter.executeQuery("SELECT * FROM hive_orders", 10);
        assertTrue(limited.isSuccess(), "LIMIT 查询失败: " + limited.getErrorMessage());
        System.out.println("[T22-hdfs] LIMIT 10 -> rowCount=" + limited.getRowCount());
        assertTrue(limited.getRowCount() <= 10, "返回行数应不超过 10，实际: " + limited.getRowCount());
        assertEquals(10, limited.getRowCount(), "数据有 200 行，应被 LIMIT 截到 10，实际: " + limited.getRowCount());

        // 连接测试两条路径（工厂临时适配器 / 服务层按 id）
        assertTrue(dataSourceFactory.testConnection(ds.getType(), ds.toAdapterConfig()),
                "工厂临时适配器连接测试应通过");
        assertTrue(dataSourceService.testConnection(ds.getId()),
                "服务层连接测试应通过");
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void hdfsAgentAnswersOrdersPerCity() {
        Assumptions.assumeTrue(portReachable(9870), "本机 WebHDFS 未启动，跳过 HDFS 端到端验证");
        Assumptions.assumeTrue(portReachable(3306), "本机 MySQL 未启动，跳过 HDFS 端到端验证");
        Assumptions.assumeTrue(portReachable(11434), "本机 Ollama 未启动，跳过 HDFS 端到端验证");
        DataSource ds = hdfsDatasource();

        // 直接装配默认 ReActAgent（本地 Ollama qwen2.5-coder:7b），问题按提示词约定带数据源 ID
        String question = "数据源ID为 " + ds.getId() + "。这是 HDFS 上的订单文件数据源，"
                + "hive_orders 表记录订单，请统计每个城市分别有多少订单。";
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("t22-hdfs-e2e-" + UUID.randomUUID())
                .build();

        List<AgentEvent> events;
        long startNanos = System.nanoTime();
        try {
            events = nl2sqlAgent.streamEvents(question, ctx).collectList().block();
        } catch (RuntimeException e) {
            throw new AssertionError("Agent 执行失败: " + e.getMessage(), e);
        }
        long costMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertNotNull(events, "streamEvents 应产出行事件");
        assertFalse(events.isEmpty(), "streamEvents 不应为空流");

        List<String> toolCalls = new ArrayList<>();
        Map<String, StringBuilder> inputBuffers = new LinkedHashMap<>();
        Map<String, StringBuilder> resultBuffers = new LinkedHashMap<>();
        String answer = "";
        for (AgentEvent event : events) {
            if (event instanceof ToolCallStartEvent tcs && tcs.getToolCallName() != null) {
                toolCalls.add(tcs.getToolCallName());
                inputBuffers.put(tcs.getToolCallId(), new StringBuilder());
                resultBuffers.put(tcs.getToolCallId(), new StringBuilder());
            } else if (event instanceof ToolCallDeltaEvent tcd && tcd.getDelta() != null) {
                inputBuffers.computeIfAbsent(tcd.getToolCallId(), k -> new StringBuilder()).append(tcd.getDelta());
            } else if (event instanceof ToolResultTextDeltaEvent trd && trd.getDelta() != null) {
                resultBuffers.computeIfAbsent(trd.getToolCallId(), k -> new StringBuilder()).append(trd.getDelta());
            } else if (event instanceof AgentResultEvent result && result.getResult() != null) {
                answer = result.getResult().getTextContent();
            }
        }

        System.out.println("[T22-hdfs-e2e] Q: " + question);
        System.out.println("[T22-hdfs-e2e] 工具调用序列: " + String.join(" -> ", toolCalls));
        inputBuffers.forEach((id, buffer) -> {
            if (buffer.length() > 0) {
                System.out.println("[T22-hdfs-e2e] 调用入参: " + truncate(buffer.toString(), 300));
            }
        });
        resultBuffers.values().stream().filter(b -> b.length() > 0).findFirst().ifPresent(
                b -> System.out.println("[T22-hdfs-e2e] 工具返回(前 300 字): " + truncate(b.toString(), 300)));
        System.out.println("[T22-hdfs-e2e] 最终回答(前 300 字): " + truncate(answer, 300));
        System.out.println("[T22-hdfs-e2e] 耗时=" + costMs + "ms 事件数=" + events.size());

        assertTrue(toolCalls.contains("execute_sql"),
                "应至少调用一次 execute_sql，实际调用序列: " + toolCalls);
        // 真实数据痕迹：计数 40 或任一中文城市名（7B 模型可能作答简略，不做措辞强约束）
        boolean mentionsCount = answer.contains("40");
        boolean mentionsCity = CITIES.stream().anyMatch(answer::contains);
        assertTrue(mentionsCount || mentionsCity,
                "回答应包含真实数据痕迹（各城市 40 条 / 城市名之一），实际回答: " + truncate(answer, 200));
    }

    /** 按名称定位 T22 入库的 HDFS 数据源；查不到直接断言失败并提示补记录。 */
    private DataSource hdfsDatasource() {
        DataSource ds = dataSourceMapper.selectOne(new LambdaQueryWrapper<DataSource>()
                .eq(DataSource::getName, HDFS_DATASOURCE_NAME)
                .eq(DataSource::getType, "hdfs")
                .orderByAsc(DataSource::getId)
                .last("LIMIT 1"));
        assertNotNull(ds, "nlp2sql_meta.datasources 中缺少 name='" + HDFS_DATASOURCE_NAME
                + "' 的 hdfs 记录，请先在本机元数据库补一条指向 localhost:9870 根目录 /demo 的数据源");
        return ds;
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

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "(null)";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
