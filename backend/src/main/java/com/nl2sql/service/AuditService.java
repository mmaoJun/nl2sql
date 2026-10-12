package com.nl2sql.service;

import com.nl2sql.mapper.AuditLogMapper;
import com.nl2sql.model.AuditLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 审计日志服务。
 *
 * <p>记录每次 NL2SQL 问答的完整执行过程，包括输入、生成 SQL、校验结果、执行结果、
 * 耗时、实际使用的模型，以及本次提问所属的会话与轮次（归因用，T11 接入）。
 * 由 {@link ChatService} 在问答成功/失败（含异常终止）后调用，确保无论成败均写入审计记录。
 *
 * <p><b>本方法不抛异常</b>：它在问答链路的收尾处被调用，一旦抛出就会覆盖主流程的
 * 真实异常 —— 用户会看到"审计写库失败"而不是"SQL 执行失败"，排障方向被带偏。
 * 审计是旁路记录，写不进去不该影响主流程的成败与可解释性。
 *
 * @see com.nl2sql.model.AuditLog
 */
@Service
@Slf4j
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    public AuditService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 写入审计日志，失败只记 error 日志。
     *
     * @param userId       操作用户 ID，未认证时为 null
     * @param datasourceId 数据源 ID
     * @param nlInput      用户输入的自然语言（原话，不是包装后的提问）
     * @param generatedSql 模型生成的 SQL
     * @param context      本次查询的结果类字段（校验/执行结果、耗时、错误信息、模型、会话归因）
     */
    public void log(Long userId, Long datasourceId, String nlInput, String generatedSql,
                    AuditContext context) {
        try {
            AuditLog entity = new AuditLog();
            entity.setUserId(userId);
            entity.setDatasourceId(datasourceId);
            entity.setNlInput(nlInput);
            entity.setGeneratedSql(generatedSql);
            entity.setValidationPassed(context.validationPassed());
            entity.setExecutionSuccess(context.executionSuccess());
            entity.setRowCount(context.rowCount());
            entity.setExecutionTimeMs(context.executionTimeMs());
            entity.setErrorMessage(context.errorMessage());
            entity.setModelName(context.modelName());
            entity.setModelProvider(context.modelProvider());
            entity.setSessionId(context.sessionId());
            entity.setTurnId(context.turnId());
            auditLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("审计日志写入失败，主流程不受影响: datasourceId={}, nlInput={}",
                    datasourceId, nlInput, e);
        }
    }
}
