package com.nl2sql.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.mapper.DataSourceMapper;
import com.nl2sql.model.DataSource;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import org.junit.jupiter.api.Assumptions;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端用例共享支撑：环境守卫、目标数据源定位、Agent 单轮问答的轨迹采集。
 *
 * <p>轨迹走 {@code agent.streamEvents(question, ctx)}（W4 前端 SSE 将使用同一条流），
 * 从中提取：按序工具调用名（{@code ToolCallStartEvent}）、每次调用的入参 JSON
 * （{@code ToolCallDeltaEvent} 增量拼接）、工具结果文本（{@code ToolResultTextDeltaEvent}
 * 增量拼接）与最终回答（{@code AgentResultEvent}）。每个问题用独立 sessionId 隔离会话状态。
 */
final class AgentE2ESupport {

    /** 目标测试库：按库名定位数据源，不硬编码 id（与 T7 一致）。 */
    static final String TARGET_DATABASE = "nlp2sql_test";

    private AgentE2ESupport() {
    }

    /** 一次工具调用的观测：工具名 + 入参 JSON + 结果文本。 */
    record CallTrace(String name, String input, String result) {
    }

    /** 一次问答的观测结果。 */
    record RunTrace(String question, List<CallTrace> calls, String answer, long costMs, int eventCount) {

        List<String> toolCalls() {
            return calls.stream().map(CallTrace::name).toList();
        }

        boolean executedSql() {
            return toolCalls().contains("execute_sql");
        }
    }

    /** MySQL 与本地 Ollama 都在才执行本地端到端，否则跳过（保证无服务环境构建通过）。 */
    static void assumeLocalStackUp() {
        Assumptions.assumeTrue(portReachable(3306), "本机 MySQL 未启动，跳过本地端到端验证");
        Assumptions.assumeTrue(portReachable(11434), "本机 Ollama 未启动，跳过本地端到端验证");
    }

    /** 云端冒烟只需目标库可达。 */
    static void assumeTargetDatabaseUp() {
        Assumptions.assumeTrue(portReachable(3306), "本机 MySQL 未启动，跳过云端冒烟验证");
    }

    /** 按库名定位 nlp2sql_test 数据源 id；查不到直接断言失败并提示补记录。 */
    static Long nlp2sqlTestDatasourceId(DataSourceMapper mapper) {
        DataSource ds = mapper.selectOne(new LambdaQueryWrapper<DataSource>()
                .eq(DataSource::getDatabaseName, TARGET_DATABASE)
                .orderByAsc(DataSource::getId)
                .last("LIMIT 1"));
        assertNotNull(ds, "nlp2sql_meta.datasources 中缺少 database_name='" + TARGET_DATABASE
                + "' 的记录，请先在本机元数据库补一条指向该库的 mysql 数据源");
        return ds.getId();
    }

    /**
     * 向 Agent 提一个问题并采集全量轨迹。
     *
     * @param agent    已装配的 ReActAgent
     * @param question 自然语言问题（需含"数据源ID为 N"）
     * @return 轨迹（工具序列 / 入参 / 结果 / 最终回答 / 耗时）
     */
    static RunTrace ask(ReActAgent agent, String question) {
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("t5-e2e-" + UUID.randomUUID())
                .build();

        long startNanos = System.nanoTime();
        List<AgentEvent> events;
        try {
            events = agent.streamEvents(question, ctx).collectList().block();
        } catch (RuntimeException e) {
            throw new AssertionError("Agent 执行失败: " + e.getMessage(), e);
        }
        long costMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertNotNull(events, "streamEvents 应产出行事件，实际为 null");
        assertTrue(!events.isEmpty(), "streamEvents 不应为空流");

        Map<String, String> idToName = new LinkedHashMap<>();
        Map<String, StringBuilder> inputBuffers = new LinkedHashMap<>();
        Map<String, StringBuilder> resultBuffers = new LinkedHashMap<>();
        String answer = "";

        for (AgentEvent event : events) {
            if (event instanceof ToolCallStartEvent tcs) {
                if (tcs.getToolCallName() != null) {
                    idToName.put(tcs.getToolCallId(), tcs.getToolCallName());
                    inputBuffers.put(tcs.getToolCallId(), new StringBuilder());
                    resultBuffers.put(tcs.getToolCallId(), new StringBuilder());
                }
            } else if (event instanceof ToolCallDeltaEvent tcd && tcd.getDelta() != null) {
                StringBuilder buffer = inputBuffers.get(tcd.getToolCallId());
                if (buffer != null) {
                    buffer.append(tcd.getDelta());
                }
            } else if (event instanceof ToolResultTextDeltaEvent trd && trd.getDelta() != null) {
                StringBuilder buffer = resultBuffers.get(trd.getToolCallId());
                if (buffer != null) {
                    buffer.append(trd.getDelta());
                }
            } else if (event instanceof AgentResultEvent result && result.getResult() != null) {
                answer = result.getResult().getTextContent();
            }
        }

        List<CallTrace> calls = new ArrayList<>();
        idToName.forEach((id, name) -> calls.add(new CallTrace(name,
                text(inputBuffers.get(id)), text(resultBuffers.get(id)))));

        RunTrace trace = new RunTrace(question, calls, answer, costMs, events.size());
        printTrace(trace);
        return trace;
    }

    /** 把轨迹关键行打到 surefire 输出，供报告留证。 */
    static void printTrace(RunTrace trace) {
        System.out.println("[T5-e2e] Q: " + trace.question());
        System.out.println("[T5-e2e] 工具调用序列: " + String.join(" -> ", trace.toolCalls()));
        for (CallTrace call : trace.calls()) {
            if (call.input() != null && !call.input().isBlank()) {
                System.out.println("[T5-e2e] 调用入参 " + call.name() + ": " + truncate(call.input(), 300));
            }
        }
        trace.calls().stream()
                .filter(call -> "execute_sql".equals(call.name()) && call.result() != null && !call.result().isBlank())
                .findFirst()
                .ifPresent(call -> System.out.println("[T5-e2e] execute_sql 返回(前 300 字): "
                        + truncate(call.result(), 300)));
        System.out.println("[T5-e2e] 最终回答(前 300 字): " + truncate(trace.answer(), 300));
        System.out.println("[T5-e2e] 耗时=" + trace.costMs() + "ms 事件数=" + trace.eventCount());
    }

    private static String text(StringBuilder buffer) {
        return buffer == null ? "" : buffer.toString();
    }

    static boolean portReachable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String truncate(String text, int maxLength) {
        if (text == null) {
            return "(null)";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
