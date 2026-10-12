package com.nl2sql.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话轮次实体，映射 {@code chat_turns} 表。
 *
 * <p><b>一轮一行</b>而不是一问一答两行：多轮追问需要"原话 + 助手回答 + SQL"落在同一行，
 * 取上下文的查询才能一次命中；拆两行就得自连接。
 *
 * <p>刻意<b>不存结果行</b>：一次查询可能上百行十几列，入库既占空间又会留下一份会过期的
 * 数据副本。历史会话只显示 SQL 与"当时返回 N 行"，要数据就重新提问。
 *
 * <p>{@code resolvedQuestion} / {@code rewriteStatus} 是为追问改写预留的列：本项目不做改写，
 * 实体不写这两个字段（{@code resolved_question} 落 NULL，{@code rewrite_status} 取库默认
 * {@code skipped}）。
 *
 * @see ChatSession
 * @see com.nl2sql.common.TurnState
 */
@Data
@TableName("chat_turns")
public class ChatTurn {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 ID */
    private Long sessionId;

    /** 用户原话 */
    private String question;

    /** 改写后的独立问题，本项目不做改写，恒为 NULL */
    private String resolvedQuestion;

    /** 改写状态，本项目不做改写，恒为库默认值 skipped */
    private String rewriteStatus;

    /** 本轮实际查询的数据源 ID */
    private Long datasourceId;

    /** 本轮实际调用的模型 id */
    private String modelName;

    /** 本轮实际调用的 provider */
    private String modelProvider;

    /** 本轮生成的 SQL，失败时可能为 null */
    private String generatedSql;

    /** 结果行数，失败时为 null */
    private Integer rowCount;

    /** 端到端耗时（毫秒） */
    private Long executionTimeMs;

    /** 本轮结果：ok / failed / empty，取值见 {@link com.nl2sql.common.TurnState} */
    private String status;

    /** 失败原因，成功时为 null */
    private String errorMessage;

    /** 本轮助手回答文本（历史注入用）；失败或空回答时为 null */
    private String answer;

    /** 创建时间，插入时自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
