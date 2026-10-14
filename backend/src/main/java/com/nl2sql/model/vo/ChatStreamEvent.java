package com.nl2sql.model.vo;

/**
 * 聊天流式事件（服务层内部载体）：事件名 + 载荷，由 {@code ChatController}
 * 映射为 {@code ServerSentEvent}。
 *
 * <p>事件名即 SSE 契约中的 {@code event:} 字段取值，常量集中在本类型上：
 * <ul>
 *   <li>{@value #EVENT_TEXT_DELTA} — 载荷 {@link TextDeltaPayload}（可选，本地通道可能缺失）</li>
 *   <li>{@value #EVENT_TOOL_CALL} — 载荷 {@link AgentTraceItem}（type=TOOL_CALL）</li>
 *   <li>{@value #EVENT_TOOL_RESULT} — 载荷 {@link AgentTraceItem}（type=TOOL_RESULT）</li>
 *   <li>{@value #EVENT_SQL_RESULT} — 载荷 {@link SqlResultPayload}（成功的 execute_sql
 *       之后立即送达；载荷不截断，供前端即时渲染 SQL 与结果表，失败调用不发）</li>
 *   <li>{@value #EVENT_FINAL} — 载荷 {@link ChatResult}（必定送达，正常结束）</li>
 *   <li>{@value #EVENT_ERROR} — 载荷 {@link ErrorPayload}（异常终止）</li>
 * </ul>
 *
 * @param event 事件名
 * @param data  载荷
 */
public record ChatStreamEvent(String event, Object data) {

    /** 正文增量（TextDeltaPayload） */
    public static final String EVENT_TEXT_DELTA = "TEXT_DELTA";

    /** 工具调用开始（AgentTraceItem，type=TOOL_CALL） */
    public static final String EVENT_TOOL_CALL = "TOOL_CALL";

    /** 工具结果返回（AgentTraceItem，type=TOOL_RESULT） */
    public static final String EVENT_TOOL_RESULT = "TOOL_RESULT";

    /**
     * SQL 执行结果（SqlResultPayload）：仅成功的 execute_sql 之后立即送达，
     * 紧跟对应的 TOOL_RESULT；载荷不截断。
     */
    public static final String EVENT_SQL_RESULT = "SQL_RESULT";

    /** 最终结果（ChatResult），事件流的最后一个事件 */
    public static final String EVENT_FINAL = "FINAL";

    /** 错误（ErrorPayload），事件流的终止事件 */
    public static final String EVENT_ERROR = "ERROR";
}
