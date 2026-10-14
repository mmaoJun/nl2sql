package com.nl2sql.service;

import com.nl2sql.model.vo.AgentTraceItem;
import com.nl2sql.model.vo.ChatResult;
import com.nl2sql.model.vo.ChatStreamEvent;
import com.nl2sql.model.vo.SqlResultPayload;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentEventCollector} 的纯单元测试：喂合成的 AgentScope 事件，校验
 * {@code SQL_RESULT} 事件的产出条件与载荷（不依赖模型 / 数据库 / Spring 容器）。
 *
 * <p>覆盖两条路径：execute_sql 成功 → 紧随 TOOL_RESULT 追加一条不截断的 SQL_RESULT；
 * execute_sql 失败 → 不发该事件（前端继续等 FINAL）。
 */
class AgentEventCollectorTest {

    private static final String CALL_ID = "call-1";

    private static final String REPLY_ID = "reply-1";

    @Test
    void successfulExecuteSqlEmitsUntruncatedSqlResult() {
        // SQL 远超 300 字符截断上限：用于证明 SQL_RESULT 未被截断（TOOL_RESULT 已被砍）
        String longSql = "SELECT " + "a".repeat(AgentEventCollector.TRUNCATE_LIMIT * 2) + " FROM users";
        String resultJson = "{\"columns\":[\"cnt\"],\"rows\":[{\"cnt\":7}],\"rowCount\":7,"
                + "\"total\":0,\"executionTimeMs\":12,\"success\":true,\"errorMessage\":null}";

        AgentEventCollector collector = new AgentEventCollector(System.nanoTime());
        List<ChatStreamEvent> events = run(collector, executeSqlEvents("execute_sql", longSql,
                resultJson, ToolResultState.SUCCESS));

        assertEquals(List.of(ChatStreamEvent.EVENT_TOOL_CALL, ChatStreamEvent.EVENT_TOOL_RESULT,
                        ChatStreamEvent.EVENT_SQL_RESULT),
                events.stream().map(ChatStreamEvent::event).toList(),
                "成功执行后事件顺序应为 TOOL_CALL → TOOL_RESULT → SQL_RESULT");

        AgentTraceItem toolCall = assertInstanceOf(AgentTraceItem.class, events.get(0).data());
        assertEquals(AgentEventCollector.TRUNCATE_LIMIT, toolCall.input().length(),
                "TOOL_CALL 的入参 JSON 按既有口径截断（长 SQL 已被砍，这正是 SQL_RESULT 要解决的问题）");
        AgentTraceItem toolResult = assertInstanceOf(AgentTraceItem.class, events.get(1).data());
        assertEquals(Boolean.TRUE, toolResult.success());

        SqlResultPayload payload = assertInstanceOf(SqlResultPayload.class, events.get(2).data());
        assertEquals(longSql, payload.getSql(), "SQL_RESULT.sql 应不截断");
        assertTrue(payload.getSql().length() > AgentEventCollector.TRUNCATE_LIMIT,
                "SQL 长度应越过截断上限，断言才有意义");
        assertEquals(List.of("cnt"), payload.getColumns());
        assertEquals(List.of(Map.of("cnt", 7)), payload.getRows());
        assertEquals(7, payload.getRowCount());
        assertEquals(12L, payload.getExecutionTimeMs(), "executionTimeMs 应取自结果 JSON");

        // FINAL 侧口径：最后一次成功 execute_sql 的数据库执行耗时同样回填
        ChatResult result = collector.toResult("test-model", 42L);
        assertEquals(12L, result.getSqlExecutionTimeMs());
        assertEquals(longSql, result.getSql());
    }

    @Test
    void failedExecuteSqlEmitsNoSqlResult() {
        String resultJson = "{\"columns\":[],\"rows\":[],\"rowCount\":0,\"executionTimeMs\":3,"
                + "\"success\":false,\"errorMessage\":\"boom\"}";

        AgentEventCollector collector = new AgentEventCollector(System.nanoTime());
        List<ChatStreamEvent> events = run(collector, executeSqlEvents("execute_sql", "SELECT broken",
                resultJson, ToolResultState.ERROR));

        assertEquals(List.of(ChatStreamEvent.EVENT_TOOL_CALL, ChatStreamEvent.EVENT_TOOL_RESULT),
                events.stream().map(ChatStreamEvent::event).toList(),
                "失败调用不应发 SQL_RESULT（前端会继续等 FINAL）");

        AgentTraceItem toolResult = assertInstanceOf(AgentTraceItem.class, events.get(1).data());
        assertEquals(Boolean.FALSE, toolResult.success(), "TOOL_RESULT 仍应照实带上失败状态");
        assertNull(collector.toResult("test-model", 42L).getSqlExecutionTimeMs(),
                "无成功 execute_sql 时 FINAL 的 sqlExecutionTimeMs 应为 null");
    }

    @Test
    void nonExecuteSqlToolEmitsNoSqlResult() {
        String resultJson = "{\"columns\":[\"cnt\"],\"rows\":[{\"cnt\":1}],\"rowCount\":1,"
                + "\"executionTimeMs\":5,\"success\":true}";

        AgentEventCollector collector = new AgentEventCollector(System.nanoTime());
        List<ChatStreamEvent> events = run(collector, executeSqlEvents("get_table_schema", "{}",
                resultJson, ToolResultState.SUCCESS));

        assertEquals(List.of(ChatStreamEvent.EVENT_TOOL_CALL, ChatStreamEvent.EVENT_TOOL_RESULT),
                events.stream().map(ChatStreamEvent::event).toList(),
                "SQL_RESULT 只对 execute_sql 产出");
        assertNotNull(collector.toResult("test-model", 42L), "无 SQL 时仍应正常汇总结果");
    }

    /**
     * 合成一次工具调用的事件序列：入参增量（拼出 sql）→ 调用结束 → 结果文本增量 → 结果结束。
     *
     * @param toolName      工具名
     * @param sql           入参 JSON 中的 sql 字段
     * @param resultJson    工具返回的结果 JSON（QueryResult 序列化形态）
     * @param endState      结果结束状态
     * @return 按 AgentScope 实际顺序排列的事件
     */
    private static List<AgentEvent> executeSqlEvents(String toolName, String sql, String resultJson,
                                                     ToolResultState endState) {
        return List.of(
                new ToolCallDeltaEvent(REPLY_ID, CALL_ID, toolName,
                        "{\"sql\":" + toJsonString(sql) + "}"),
                new ToolCallEndEvent(REPLY_ID, CALL_ID, toolName),
                new ToolResultTextDeltaEvent(REPLY_ID, CALL_ID, toolName, resultJson),
                new ToolResultEndEvent(REPLY_ID, CALL_ID, toolName, endState));
    }

    /** 逐条喂事件并按序汇总产出的 SSE 事件。 */
    private static List<ChatStreamEvent> run(AgentEventCollector collector, List<AgentEvent> events) {
        List<ChatStreamEvent> out = new ArrayList<>();
        events.forEach(event -> out.addAll(collector.accept(event)));
        return out;
    }

    /** 最小 JSON 字符串转义（测试 SQL 只含字母与空格，转义仅为语义完整）。 */
    private static String toJsonString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
