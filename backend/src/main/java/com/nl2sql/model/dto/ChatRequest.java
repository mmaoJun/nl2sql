package com.nl2sql.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 聊天请求体（{@code POST /api/v1/chat}）。
 *
 * <p>字段约束与 SSE 端点（{@code GET /api/v1/chat/stream} 的查询参数）保持一致。
 * 校验由 {@code ChatController} 手工执行并抛 {@link com.nl2sql.common.BusinessException}
 * （错误码 {@link com.nl2sql.common.ErrorCode#CHAT_INVALID_REQUEST}），
 * 以便同步与流式两个入口共用同一套错误语义；下面的 Bean Validation 注解仅作接口文档用途。
 *
 * @see com.nl2sql.model.vo.ChatResult
 */
@Data
@Schema(description = "聊天请求")
public class ChatRequest {

    /** 用户输入的自然语言问题（包装为“数据源ID为 N。+ 问题”后交给 Agent） */
    @NotBlank(message = "question 不能为空")
    @Schema(description = "自然语言问题", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "users 表中共有多少位用户？")
    private String question;

    /** 目标数据源 ID */
    @NotNull(message = "datasourceId 不能为空")
    @Schema(description = "数据源 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "5")
    private Long datasourceId;

    /** 会话 ID（{@code chat_sessions.id}），可空；为空时服务端自动新建会话并回填到 ChatResult */
    @Schema(description = "会话 ID，可空；为空时自动新建会话（T11）", example = "12")
    private Long sessionId;
}
