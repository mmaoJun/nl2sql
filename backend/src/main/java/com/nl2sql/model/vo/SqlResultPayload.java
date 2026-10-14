package com.nl2sql.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * SSE {@code SQL_RESULT} 事件载荷：一次<b>成功</b>的 {@code execute_sql} 的 SQL 与结果集。
 *
 * <p>为什么单独出这个事件：{@code TOOL_CALL.input} / {@code TOOL_RESULT.summary} 都受
 * 300 字符截断（见 {@code AgentEventCollector.TRUNCATE_LIMIT}），长 SQL 会被砍、结果集
 * 根本拿不全。本载荷<b>不截断</b>，供前端在 execute_sql 返回时即刻渲染
 * "SQL 语句 → 结果表"，不必等 {@code FINAL}（{@link ChatResult} 仍照旧是全量兜底）。
 *
 * <p>不发失败调用：执行失败时本事件缺席，前端继续等 {@code FINAL}。
 *
 * <p>为空的字段在 HTTP 响应中不序列化（全局 {@code default-property-inclusion: non_null}）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlResultPayload {

    /** 本次 execute_sql 的 SQL（入参 {@code sql} 字段原样；入参非 JSON 时为 null） */
    private String sql;

    /** 结果列名 */
    private List<String> columns;

    /** 结果数据行（不截断，前端据此渲染表格） */
    private List<Map<String, Object>> rows;

    /** 结果行数 */
    private int rowCount;

    /** 数据库执行耗时（毫秒），取自工具返回结果 JSON 的 {@code executionTimeMs} */
    private long executionTimeMs;
}
