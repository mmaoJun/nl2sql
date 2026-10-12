package com.nl2sql.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 审计日志实体，映射 {@code audit_logs} 表。
 *
 * <p>记录每次 NL2SQL 问答的完整执行过程（输入、生成 SQL、校验/执行结果、耗时、
 * 实际使用的模型，以及会话归因列——T11 接入前固定为空），
 * 由 {@link com.nl2sql.service.AuditService} 写入。
 *
 * @see com.nl2sql.service.ChatService
 */
@Data
@TableName("audit_logs")
public class AuditLog {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作用户 ID，未认证时为 null */
    private Long userId;

    /** 数据源 ID */
    private Long datasourceId;

    /** 用户输入的自然语言 */
    private String nlInput;

    /** 模型生成的 SQL */
    private String generatedSql;

    /** SQL 安全校验是否通过 */
    private Boolean validationPassed;

    /** SQL 执行是否成功 */
    private Boolean executionSuccess;

    /** 查询返回行数 */
    private Integer rowCount;

    /** 总执行耗时（毫秒） */
    private Long executionTimeMs;

    /** 错误信息，成功时为 null */
    private String errorMessage;

    /** 本次实际调用的模型 id，模型解析失败时为 null */
    private String modelName;

    /** 本次实际调用的模型提供方，模型解析失败时为 null */
    private String modelProvider;

    /** 所属会话 ID，无会话模式为 null（T11 接入） */
    private Long sessionId;

    /** 所属轮次 ID，无会话模式或轮次写入失败时为 null（T11 接入） */
    private Long turnId;

    /** 创建时间，由 MyBatis-Plus 自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
