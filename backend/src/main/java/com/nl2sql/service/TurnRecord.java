package com.nl2sql.service;

/**
 * 一轮对话的落库载体，由 {@link ChatService} 组装后交给会话层写入。
 *
 * <p>用 record 而不是让聊天服务自己 new 一个 {@link com.nl2sql.model.ChatTurn}：
 * 聊天服务不该知道"轮次是怎么存的"，而且具名组件让调用点的十余个实参有可读的顺序约束
 * （同步与流式两条通道共用同一次落库口径，传错顺序编译期发现不了）。
 *
 * <p>相比旧项目的同名载体：<b>不含</b> {@code resolvedQuestion} 与 {@code rewriteStatus}
 * （本项目不做追问改写，列留空），<b>新增</b> {@code answer}（历史注入需要助手回答文本）。
 *
 * @param sessionId       所属会话，null 表示无会话模式（不落库）
 * @param question        用户原话
 * @param datasourceId    本轮实际查询的数据源
 * @param modelName       本轮实际调用的模型 id
 * @param modelProvider   本轮实际调用的 provider
 * @param generatedSql    本轮生成的 SQL，可能在生成阶段就失败而为 null
 * @param rowCount        结果行数，失败时为 null（不写脏值）
 * @param executionTimeMs 端到端耗时（毫秒）
 * @param status          本轮结果，取值见 {@link com.nl2sql.common.TurnState}
 * @param errorMessage    失败原因，成功时为 null
 * @param answer          助手回答文本，失败或空回答时为 null
 */
public record TurnRecord(Long sessionId,
                         String question,
                         Long datasourceId,
                         String modelName,
                         String modelProvider,
                         String generatedSql,
                         Integer rowCount,
                         Long executionTimeMs,
                         String status,
                         String errorMessage,
                         String answer) {
}
