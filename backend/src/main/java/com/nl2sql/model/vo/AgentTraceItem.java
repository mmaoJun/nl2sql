package com.nl2sql.model.vo;

/**
 * Agent 轨迹项：{@link ChatResult#getTrace()} 的元素，也是 SSE {@code TOOL_CALL} /
 * {@code TOOL_RESULT} 事件的载荷。
 *
 * <p>同一次工具调用产生两个元素（共享 {@code callId}）：先 {@code TOOL_CALL}
 * （{@code input} 有值、{@code success} 为空——结果尚未返回），后 {@code TOOL_RESULT}
 * （{@code summary} 有值、{@code success} 为结果状态）。截断上限 300 字符。
 *
 * <p>为空的字段在 HTTP 响应中不序列化（全局 {@code default-property-inclusion: non_null}）。
 *
 * @param type    {@value #TYPE_TOOL_CALL} 或 {@value #TYPE_TOOL_RESULT}
 * @param tool    工具名（如 {@code execute_sql}）
 * @param callId  工具调用 ID（同一次调用的两个元素共享）
 * @param input   工具入参 JSON（截断 300 字符；TOOL_RESULT 元素为 null）
 * @param summary 工具结果文本（截断 300 字符；TOOL_CALL 元素为 null）
 * @param success 结果状态（TOOL_RESULT：true=成功；TOOL_CALL：null，结果未返回）
 */
public record AgentTraceItem(String type, String tool, String callId,
                             String input, String summary, Boolean success) {

    /** 轨迹类型：工具调用 */
    public static final String TYPE_TOOL_CALL = "TOOL_CALL";

    /** 轨迹类型：工具结果 */
    public static final String TYPE_TOOL_RESULT = "TOOL_RESULT";

    /**
     * 构造 TOOL_CALL 轨迹项。
     *
     * @param tool    工具名
     * @param callId  调用 ID
     * @param input   入参（已截断）
     * @return 轨迹项（summary / success 为空）
     */
    public static AgentTraceItem toolCall(String tool, String callId, String input) {
        return new AgentTraceItem(TYPE_TOOL_CALL, tool, callId, input, null, null);
    }

    /**
     * 构造 TOOL_RESULT 轨迹项。
     *
     * @param tool    工具名
     * @param callId  调用 ID
     * @param summary 结果文本（已截断）
     * @param success 是否成功
     * @return 轨迹项（input 为空）
     */
    public static AgentTraceItem toolResult(String tool, String callId, String summary, boolean success) {
        return new AgentTraceItem(TYPE_TOOL_RESULT, tool, callId, null, summary, success);
    }
}
