package com.nl2sql.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 聊天结果：{@code POST /api/v1/chat} 的响应数据，也是 SSE {@code FINAL} 事件的载荷。
 *
 * <p>{@code sql / columns / rows / rowCount} 取自本轮对话中最后一次<b>成功</b>的
 * {@code execute_sql} 工具调用：SQL 为其入参中的 {@code sql} 字段，结果是工具返回
 * JSON（{@code {columns, rows, rowCount, ...}}）的解析产物；解析失败时这些字段为空，
 * 原始摘要仍保留在 {@link #trace} 中。
 *
 * <p>性能口径对应“流式”：{@link #firstEventMs} 为发起调用到首个 Agent 事件到达的耗时，
 * {@link #latencyMs} 为发起调用到本轮完成的耗时。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResult {

    /** 最终回答文本（AgentResultEvent 正文；缺失时可回退为正文增量拼接） */
    private String answer;

    /**
     * 本轮问答所属会话 ID（{@code chat_sessions.id}）。
     *
     * <p>请求未带 sessionId 时服务端自动新建会话，前端应保存该值并在后续追问时回传；
     * 无会话模式（会话创建失败降级）时为 null，JSON 中省略该字段。
     */
    private Long sessionId;

    /** 最后一次成功 execute_sql 的 SQL（解析失败为 null） */
    private String sql;

    /** 结果列名（解析失败为 null） */
    private List<String> columns;

    /** 结果数据行（解析失败为 null） */
    private List<Map<String, Object>> rows;

    /** 结果行数（解析失败为 0） */
    private int rowCount;

    /**
     * 最后一次成功 execute_sql 的<b>数据库执行</b>耗时（毫秒）。
     *
     * <p>取自工具返回结果 JSON 的 {@code executionTimeMs}，与 {@link #latencyMs}
     * （端到端总耗时）口径不同；本轮没有成功的 execute_sql 时为 null（响应中省略该字段）。
     */
    private Long sqlExecutionTimeMs;

    /** 工具调用轨迹（按发生顺序） */
    private List<AgentTraceItem> trace;

    /** 本轮实际使用的模型名 */
    private String model;

    /** 本轮总耗时（毫秒） */
    private long latencyMs;

    /** 首个 Agent 事件到达耗时（毫秒） */
    private long firstEventMs;
}
