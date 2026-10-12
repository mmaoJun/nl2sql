package com.nl2sql.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话会话实体，映射 {@code chat_sessions} 表。
 *
 * <p>会话是轮次的聚合边界，也是权限边界：{@code userId} 只能由服务端从 JWT 解析后写入，
 * 不接受前端传入。会话<b>不绑定</b>单一数据源 —— 每一轮在 {@link ChatTurn} 里记自己的
 * {@code datasourceId}，这里的 {@code datasourceId} 只是新建会话时的默认值。
 *
 * <p>没有 {@code deleted} 字段：全局虽配了 logic-delete-field，但实体不含该属性时
 * MyBatis-Plus 不会套用软删，删除即物理删除。会话是用户可再生数据，留痕职责在
 * {@code audit_logs}（审计记录不随会话删除而消失）。
 *
 * @see ChatTurn
 */
@Data
@TableName("chat_sessions")
public class ChatSession {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户 ID，来自 JWT */
    private Long userId;

    /** 会话标题，首轮提问后取问题前 30 字 */
    private String title;

    /** 会话默认数据源 ID，可为空 */
    private Long datasourceId;

    /** 创建时间，插入时自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 最后活跃时间，插入与更新时自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
