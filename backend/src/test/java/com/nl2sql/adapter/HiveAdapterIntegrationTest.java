package com.nl2sql.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.adapter.jdbc.HiveAdapter;
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
import java.util.Locale;
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
 * HiveAdapter 直连本机 HiveServer2（docker/hive-hdfs 的 demo 库）集成验证。
 *
 * <p>三段（与 T21 派发说明对应）：
 * <ol>
 *   <li>{@link #factoryRegistersHiveTypeMetadata()}——类型注册元数据（code/displayName/defaultPort），
 *       不依赖任何外部服务；</li>
 *   <li>{@link #hiveAdapterReadsDemoMetadataAndGroupByQuery()}——适配器路径（不经 LLM）：
 *       经 {@link DataSourceFactory} 用新入库数据源（name='Hive 演示库'，非硬编码 id）
 *       取适配器，断言元数据与分组查询的真实返回；</li>
 *   <li>{@link #hiveAgentAnswersOrderStatusCounts()}——Agent 端到端 1 例（真实本地 7B，
 *       1 次问答预算）：直接装配默认 ReActAgent 提问，断言回答含真实数据痕迹。</li>
 * </ol>
 *
 * <p>守卫沿用 {@code AgentE2ESupport.assumeLocalStackUp} 的端口探测写法：
 * MySQL(3306) / Hive(10000) / Ollama(11434) 不达则 skip 而非 fail，
 * 保证无环境构建通过。
 */
@SpringBootTest
class HiveAdapterIntegrationTest {

    /** 演示数据源在 nlp2sql_meta.datasources 中的名称（T21 入库记录）。 */
    private static final String HIVE_DATASOURCE_NAME = "Hive 演示库";

    /** demo 库固定数据：orders 200 行，5 种状态各 40。 */
    private static final Set<String> STATUSES =
            Set.of("pending", "paid", "shipped", "completed", "cancelled");

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Autowired
    private DataSourceFactory dataSourceFactory;

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private ReActAgent nl2sqlAgent;

    /** 本用例内经工厂缓存的适配器对应的数据源 id，结束时清理，避免连接池悬挂。 */
    private Long cachedAdapterId;

    @AfterEach
    void cleanup() {
        if (cachedAdapterId != null) {
            dataSourceFactory.removeAdapter(cachedAdapterId);
        }
    }

    @Test
    void factoryRegistersHiveTypeMetadata() {
        assertTrue(dataSourceFactory.isSupported("hive"), "hive 类型应已注册");
        assertEquals("hive", dataSourceFactory.getDialect("hive"));
        assertInstanceOf(HiveAdapter.class, dataSourceFactory.createAdapter("hive"));

        DataSourceTypeInfo hive = dataSourceFactory.supportedTypes().stream()
                .filter(t -> "hive".equals(t.code()))
                .findFirst()
                .orElseThrow();
        assertEquals("Hive", hive.displayName());
        assertEquals(10000, hive.defaultPort());
    }

    @Test
    void hiveAdapterReadsDemoMetadataAndGroupByQuery() {
        Assumptions.assumeTrue(portReachable(3306), "本机 MySQL 未启动，跳过 Hive 适配器集成验证");
        Assumptions.assumeTrue(portReachable(10000), "本机 Hive(HiveServer2) 未启动，跳过 Hive 适配器集成验证");
        DataSource ds = hiveDatasource();

        // 经注册表用 datasource id 取适配器（与业务链路同一条路径）
        DataSourceAdapter adapter = dataSourceFactory.getAdapter(ds.getId(), ds.getType(), ds.toAdapterConfig());
        cachedAdapterId = ds.getId();
        assertEquals("hive", adapter.getDialect());

        List<TableInfo> tables = adapter.getTables();
        Set<String> tableNames = tables.stream().map(TableInfo::getTableName).collect(Collectors.toSet());
        System.out.println("[T21-hive] getTables() = " + tableNames);
        assertFalse(tables.isEmpty(), "demo 库应有表");
        assertTrue(tableNames.contains("orders"), "demo 库应含 orders，实际: " + tableNames);
        assertTrue(tableNames.contains("products"), "demo 库应含 products，实际: " + tableNames);

        List<ColumnInfo> columns = adapter.getColumns("orders");
        List<String> columnNames = columns.stream().map(ColumnInfo::getColumnName).toList();
        System.out.println("[T21-hive] getColumns(orders) = " + columnNames);
        assertFalse(columns.isEmpty(), "orders 应有列");
        assertTrue(columnNames.contains("status"), "orders 应含 status 列，实际: " + columnNames);

        QueryResult result = adapter.executeQuery(
                "SELECT status, COUNT(*) AS cnt FROM orders GROUP BY status", 100);
        assertTrue(result.isSuccess(), "分组查询失败: " + result.getErrorMessage());
        System.out.println("[T21-hive] columns=" + result.getColumns() + " rows=" + result.getRows());
        assertEquals(5, result.getRowCount(), "orders 应有 5 种状态，实际: " + result.getRows());
        for (Map<String, Object> row : result.getRows()) {
            assertEquals(40L, ((Number) row.get("cnt")).longValue(),
                    "每种状态应恰好 40 条，实际行: " + row);
            assertTrue(STATUSES.contains(String.valueOf(row.get("status")).toLowerCase(Locale.ROOT)),
                    "状态应为演示数据五值之一，实际行: " + row);
        }

        // 连接测试两条路径（工厂临时适配器 / 服务层按 id）
        assertTrue(dataSourceFactory.testConnection(ds.getType(), ds.toAdapterConfig()),
                "工厂临时适配器连接测试应通过");
        assertTrue(dataSourceService.testConnection(ds.getId()),
                "服务层连接测试应通过");
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void hiveAgentAnswersOrderStatusCounts() {
        Assumptions.assumeTrue(portReachable(3306), "本机 MySQL 未启动，跳过 Hive 端到端验证");
        Assumptions.assumeTrue(portReachable(10000), "本机 Hive(HiveServer2) 未启动，跳过 Hive 端到端验证");
        Assumptions.assumeTrue(portReachable(11434), "本机 Ollama 未启动，跳过 Hive 端到端验证");
        DataSource ds = hiveDatasource();

        // 直接装配默认 ReActAgent（本地 Ollama qwen2.5-coder:7b），问题按提示词约定带数据源 ID
        String question = "数据源ID为 " + ds.getId() + "。这是 Hive 数据源（demo 库），orders 表记录订单，"
                + "请统计订单表里各状态分别有多少条。";
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("t21-hive-e2e-" + UUID.randomUUID())
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

        System.out.println("[T21-hive-e2e] Q: " + question);
        System.out.println("[T21-hive-e2e] 工具调用序列: " + String.join(" -> ", toolCalls));
        inputBuffers.forEach((id, buffer) -> {
            if (buffer.length() > 0) {
                System.out.println("[T21-hive-e2e] 调用入参: " + truncate(buffer.toString(), 300));
            }
        });
        resultBuffers.values().stream().filter(b -> b.length() > 0).findFirst().ifPresent(
                b -> System.out.println("[T21-hive-e2e] 工具返回(前 300 字): " + truncate(b.toString(), 300)));
        System.out.println("[T21-hive-e2e] 最终回答(前 300 字): " + truncate(answer, 300));
        System.out.println("[T21-hive-e2e] 耗时=" + costMs + "ms 事件数=" + events.size());

        assertTrue(toolCalls.contains("execute_sql"),
                "应至少调用一次 execute_sql，实际调用序列: " + toolCalls);
        // 真实数据痕迹：计数 40 或五个状态名之一（7B 模型可能作答简略，不做措辞强约束）
        String lowerAnswer = answer.toLowerCase(Locale.ROOT);
        boolean mentionsCount = answer.contains("40");
        boolean mentionsStatus = STATUSES.stream().anyMatch(lowerAnswer::contains);
        assertTrue(mentionsCount || mentionsStatus,
                "回答应包含真实数据痕迹（各状态 40 条 / 状态名之一），实际回答: " + truncate(answer, 200));
    }

    /** 按名称定位 T21 入库的 Hive 数据源；查不到直接断言失败并提示补记录。 */
    private DataSource hiveDatasource() {
        DataSource ds = dataSourceMapper.selectOne(new LambdaQueryWrapper<DataSource>()
                .eq(DataSource::getName, HIVE_DATASOURCE_NAME)
                .eq(DataSource::getType, "hive")
                .orderByAsc(DataSource::getId)
                .last("LIMIT 1"));
        assertNotNull(ds, "nlp2sql_meta.datasources 中缺少 name='" + HIVE_DATASOURCE_NAME
                + "' 的 hive 记录，请先在本机元数据库补一条指向 localhost:10000/demo 的数据源");
        return ds;
    }

    /** 端口探测（与 AgentE2ESupport.assumeLocalStackUp 同写法）。 */
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
