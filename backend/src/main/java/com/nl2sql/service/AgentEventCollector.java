package com.nl2sql.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.model.vo.AgentTraceItem;
import com.nl2sql.model.vo.ChatResult;
import com.nl2sql.model.vo.ChatStreamEvent;
import com.nl2sql.model.vo.TextDeltaPayload;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产侧 Agent 事件采集器（不依赖 test 的 {@code AgentE2ESupport}）。
 *
 * <p>消费 {@code agent.streamEvents(...)} 产出的 {@link AgentEvent} 流，边采集边产出
 * SSE 域事件，并在流结束后汇总为 {@link ChatResult}。同步问答（{@link ChatService#ask}）
 * 与 SSE 流式（{@link ChatService#stream}）共用本采集器，保证两条通道的
 * trace / sql / rows 提取口径完全一致。
 *
 * <p><b>事件映射</b>（AgentScope 事件 → SSE 事件）：
 * <ul>
 *   <li>{@code TextBlockDeltaEvent} → {@code TEXT_DELTA}（正文增量；本地通道可能缺失）</li>
 *   <li>{@code ToolCallEndEvent}（兜底 {@code ToolResultStartEvent}）→ {@code TOOL_CALL}，
 *       此时入参增量已拼全；每次调用只发一次</li>
 *   <li>{@code ToolResultEndEvent} → {@code TOOL_RESULT}（携带结果状态 success）</li>
 *   <li>{@code AgentResultEvent} → 最终回答（不出事件，汇总进 FINAL）</li>
 *   <li>{@code ExceedMaxItersEvent} → 仅告警日志（轨迹协议不含该类型）</li>
 * </ul>
 *
 * <p>非线程安全：单次问答一个实例，只在同一条流的订阅线程上使用。
 */
@Slf4j
public final class AgentEventCollector {

    /** trace 中 input / summary 的截断长度 */
    static final int TRUNCATE_LIMIT = 300;

    /** 用于提取 SQL / 结果集的工具名 */
    private static final String EXECUTE_SQL_TOOL = "execute_sql";

    private static final TypeReference<List<Map<String, Object>>> ROWS_TYPE = new TypeReference<>() {
    };

    /** 问答发起时刻（用于 firstEventMs / latencyMs 口径） */
    private final long startNanos;

    /** 首个事件到达时刻（纳秒），-1 表示还没有事件 */
    private long firstEventNanos = -1L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 工具调用状态（按发生顺序） */
    private final List<Call> calls = new ArrayList<>();

    /** callId → 工具调用状态 */
    private final Map<String, Call> callsById = new HashMap<>();

    /** 正文增量缓冲（AgentResultEvent 缺失时的回答回退） */
    private final StringBuilder textBuffer = new StringBuilder();

    /** AgentResultEvent 给出的最终回答 */
    private String answer = "";

    /**
     * @param startNanos 问答发起时刻（{@link System#nanoTime()}），用于耗时口径
     */
    public AgentEventCollector(long startNanos) {
        this.startNanos = startNanos;
    }

    /** 一次工具调用的采集状态。 */
    private static final class Call {

        final String callId;
        String name;
        /** 入参增量拼接缓冲（JSON 文本） */
        final StringBuilder input = new StringBuilder();
        /** 结果文本增量拼接缓冲（execute_sql 为 QueryResult JSON） */
        final StringBuilder result = new StringBuilder();
        /** TOOL_CALL 事件是否已发出（保证每次调用只发一次、先于 TOOL_RESULT） */
        boolean callEmitted;
        /** 是否收到 ToolResultEndEvent */
        boolean resultEnded;
        /** 结果状态（SUCCESS=true），未知为 null */
        Boolean success;

        Call(String callId) {
            this.callId = callId;
        }
    }

    /**
     * 消费一个事件：更新采集状态，并返回本次事件触发的 SSE 事件（可能为空）。
     *
     * @param event Agent 事件（null 将被忽略）
     * @return 需要推给前端的 SSE 域事件（按序）
     */
    public List<ChatStreamEvent> accept(AgentEvent event) {
        if (event == null) {
            return List.of();
        }
        if (firstEventNanos < 0) {
            firstEventNanos = System.nanoTime();
        }
        List<ChatStreamEvent> out = new ArrayList<>(2);

        if (event instanceof TextBlockDeltaEvent e) {
            if (e.getDelta() != null && !e.getDelta().isEmpty()) {
                textBuffer.append(e.getDelta());
                out.add(new ChatStreamEvent(ChatStreamEvent.EVENT_TEXT_DELTA,
                        new TextDeltaPayload(e.getDelta())));
            }
        } else if (event instanceof ToolCallStartEvent e) {
            obtain(e.getToolCallId(), e.getToolCallName());
        } else if (event instanceof ToolCallDeltaEvent e) {
            Call call = obtain(e.getToolCallId(), e.getToolCallName());
            if (e.getDelta() != null) {
                call.input.append(e.getDelta());
            }
        } else if (event instanceof ToolCallEndEvent e) {
            obtain(e.getToolCallId(), e.getToolCallName());
            emitToolCallIfNeeded(callOf(e.getToolCallId()), out);
        } else if (event instanceof ToolResultStartEvent e) {
            // 兜底：若个别链路上没有 ToolCallEndEvent，在结果开始前补发 TOOL_CALL，保持顺序
            obtain(e.getToolCallId(), e.getToolCallName());
            emitToolCallIfNeeded(callOf(e.getToolCallId()), out);
        } else if (event instanceof ToolResultTextDeltaEvent e) {
            Call call = obtain(e.getToolCallId(), e.getToolCallName());
            if (e.getDelta() != null) {
                call.result.append(e.getDelta());
            }
        } else if (event instanceof ToolResultEndEvent e) {
            Call call = obtain(e.getToolCallId(), e.getToolCallName());
            emitToolCallIfNeeded(call, out);
            call.resultEnded = true;
            call.success = e.getState() == ToolResultState.SUCCESS;
            out.add(new ChatStreamEvent(ChatStreamEvent.EVENT_TOOL_RESULT,
                    AgentTraceItem.toolResult(call.name, call.callId,
                            truncate(call.result.toString()), Boolean.TRUE.equals(call.success))));
        } else if (event instanceof AgentResultEvent e) {
            if (e.getResult() != null && e.getResult().getTextContent() != null) {
                answer = e.getResult().getTextContent();
            }
        } else if (event instanceof ExceedMaxItersEvent e) {
            log.warn("[chat] Agent 超过最大迭代轮数: maxIters={} currentIter={}",
                    e.getMaxIters(), e.getCurrentIter());
        }
        return out;
    }

    /**
     * 汇总本轮采集结果为 {@link ChatResult}。
     *
     * @param model     本轮实际使用的模型名
     * @param latencyMs 发起调用到完成的耗时（毫秒）
     * @return 聊天结果（trace 覆盖全部工具调用；sql/columns/rows 取最后一次成功 execute_sql）
     */
    public ChatResult toResult(String model, long latencyMs) {
        List<AgentTraceItem> trace = new ArrayList<>(calls.size() * 2);
        for (Call call : calls) {
            trace.add(AgentTraceItem.toolCall(call.name, call.callId, truncate(call.input.toString())));
            if (call.resultEnded) {
                trace.add(AgentTraceItem.toolResult(call.name, call.callId,
                        truncate(call.result.toString()), Boolean.TRUE.equals(call.success)));
            }
        }

        String sql = null;
        List<String> columns = null;
        List<Map<String, Object>> rows = null;
        int rowCount = 0;
        Call lastExecute = lastSuccessfulExecuteSqlCall();
        if (lastExecute != null) {
            sql = extractSqlArgument(lastExecute);
            ParsedQuery parsed = parseQueryResult(lastExecute);
            if (parsed != null) {
                columns = parsed.columns();
                rows = parsed.rows();
                rowCount = parsed.rowCount();
            }
        }

        return ChatResult.builder()
                .answer(answerText())
                .sql(sql)
                .columns(columns)
                .rows(rows)
                .rowCount(rowCount)
                .trace(trace)
                .model(model)
                .latencyMs(latencyMs)
                .firstEventMs(firstEventMs())
                .build();
    }

    /**
     * 首个事件到达耗时（发起调用 → 首个 Agent 事件）。
     *
     * @return 毫秒；无事件时为 0
     */
    public long firstEventMs() {
        if (firstEventNanos < 0) {
            return 0L;
        }
        return (firstEventNanos - startNanos) / 1_000_000;
    }

    /** 最终回答：优先 AgentResultEvent 正文，缺失时回退为正文增量拼接。 */
    private String answerText() {
        if (answer != null && !answer.isBlank()) {
            return answer;
        }
        return textBuffer.toString();
    }

    /** 最后一次成功的 execute_sql 调用（成功 = ToolResultEnd 状态为 SUCCESS）。 */
    private Call lastSuccessfulExecuteSqlCall() {
        for (int i = calls.size() - 1; i >= 0; i--) {
            Call call = calls.get(i);
            if (EXECUTE_SQL_TOOL.equals(call.name) && Boolean.TRUE.equals(call.success)) {
                return call;
            }
        }
        return null;
    }

    /**
     * 本轮最后一次 execute_sql 调用入参中的 sql（<b>不论该次调用是否成功</b>）。
     *
     * <p>审计用：失败路径下 {@link ChatResult#getSql()} 为空（它只取成功的调用），
     * 本方法仍能取到模型生成、被安全校验或执行环节拒绝的那支 SQL。
     *
     * @return SQL 文本；本轮未调用过 execute_sql 或入参非 JSON 时为 null
     */
    public String lastRequestedSql() {
        for (int i = calls.size() - 1; i >= 0; i--) {
            Call call = calls.get(i);
            if (EXECUTE_SQL_TOOL.equals(call.name)) {
                return extractSqlArgument(call);
            }
        }
        return null;
    }

    /** 从 execute_sql 入参 JSON 中取 sql 字段；解析失败返回 null（原文本已在 trace）。 */
    private String extractSqlArgument(Call call) {
        String inputText = call.input.toString();
        if (inputText.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(inputText);
            JsonNode sql = node.get("sql");
            return sql != null && sql.isTextual() ? sql.asText() : null;
        } catch (Exception e) {
            log.warn("[chat] execute_sql 入参非 JSON，sql 字段置空（原始入参已在 trace）: {}", truncate(inputText));
            return null;
        }
    }

    /** 解析 execute_sql 结果 JSON（QueryResult）；解析失败返回 null（原始摘要已在 trace）。 */
    private ParsedQuery parseQueryResult(Call call) {
        String text = call.result.toString();
        if (text.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(text);
            List<String> columns = new ArrayList<>();
            JsonNode columnsNode = root.get("columns");
            if (columnsNode != null && columnsNode.isArray()) {
                columnsNode.forEach(node -> columns.add(node.asText()));
            }
            List<Map<String, Object>> rows = root.hasNonNull("rows")
                    ? objectMapper.convertValue(root.get("rows"), ROWS_TYPE)
                    : List.of();
            int rowCount = root.hasNonNull("rowCount") ? root.get("rowCount").asInt() : rows.size();
            return new ParsedQuery(columns, rows, rowCount);
        } catch (Exception e) {
            log.warn("[chat] execute_sql 结果非 JSON，columns/rows 置空（原始摘要已在 trace）: {}", truncate(text));
            return null;
        }
    }

    /** execute_sql 结果解析产物。 */
    private record ParsedQuery(List<String> columns, List<Map<String, Object>> rows, int rowCount) {
    }

    /** 取（或登记）一次工具调用状态；重复 callId 返回已有状态并补全名称。 */
    private Call obtain(String callId, String name) {
        Call call = callsById.get(callId);
        if (call == null) {
            call = new Call(callId);
            callsById.put(callId, call);
            calls.add(call);
        }
        if (call.name == null && name != null) {
            call.name = name;
        }
        return call;
    }

    /** obtain 的非空版（ToolCallEndEvent 等场景调用前已登记）。 */
    private Call callOf(String callId) {
        return callsById.get(callId);
    }

    /** 发 TOOL_CALL 事件（每次调用仅一次；结果事件前必定先发）。 */
    private void emitToolCallIfNeeded(Call call, List<ChatStreamEvent> out) {
        if (call == null || call.callEmitted) {
            return;
        }
        call.callEmitted = true;
        out.add(new ChatStreamEvent(ChatStreamEvent.EVENT_TOOL_CALL,
                AgentTraceItem.toolCall(call.name, call.callId, truncate(call.input.toString()))));
    }

    /** 截断到 {@value #TRUNCATE_LIMIT} 字符；空文本返回 null（响应中省略该字段）。 */
    static String truncate(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        return text.length() <= TRUNCATE_LIMIT ? text : text.substring(0, TRUNCATE_LIMIT);
    }
}
