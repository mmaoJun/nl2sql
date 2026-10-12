package com.nl2sql.service;

/**
 * 一次问答的结果类审计字段。
 *
 * <p>把这些字段收进 record，是因为 {@code AuditService.log} 的实参已经排到十几个，
 * 全是 {@code Long}/{@code String}/{@code boolean}，调用点传错顺序编译期发现不了。
 *
 * <p>{@code sessionId} 与 {@code turnId} 只用于<b>归因</b>（从一条审计记录跳回它所属的
 * 对话），审计本身不是上下文来源 —— 它是运维流水账，失败重试会写入多行、
 * 校验失败的行也在里面，拿它当历史需要一堆过滤条件。
 *
 * @param userId           操作用户 ID，未认证时为 null
 * @param validationPassed SQL 安全校验是否通过
 * @param executionSuccess SQL 是否执行成功
 * @param rowCount         结果行数
 * @param executionTimeMs  端到端耗时（毫秒）
 * @param errorMessage     失败原因，成功时为 null
 * @param modelName        本次真实调用的模型 id，模型解析失败时为 null
 * @param modelProvider    本次真实调用的 provider，模型解析失败时为 null
 * @param sessionId        所属会话，无会话模式为 null（T11 接入）
 * @param turnId           所属轮次，无会话模式或轮次写入失败时为 null（T11 接入）
 */
public record AuditContext(Long userId,
                           boolean validationPassed,
                           boolean executionSuccess,
                           Integer rowCount,
                           Long executionTimeMs,
                           String errorMessage,
                           String modelName,
                           String modelProvider,
                           Long sessionId,
                           Long turnId) {
}
