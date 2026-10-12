package com.nl2sql.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建会话请求体（{@code POST /api/v1/sessions}）。
 *
 * <p>刻意没有 {@code userId} 字段：归属只能由服务端从 JWT 解析，
 * 让前端传 userId 等于把越权访问做成一个参数。
 *
 * @see com.nl2sql.controller.SessionController
 */
@Data
@Schema(description = "新建会话请求")
public class SessionCreateRequest {

    /** 会话标题，可空；空则用"新对话"，首轮提问后自动改成问题前 30 字 */
    @Size(max = 100, message = "会话标题最长 100 字")
    @Schema(description = "会话标题，可空", example = "各部门人数排查")
    private String title;

    /** 会话默认数据源 ID，可空 */
    @Schema(description = "会话默认数据源 ID", example = "1")
    private Long datasourceId;
}
