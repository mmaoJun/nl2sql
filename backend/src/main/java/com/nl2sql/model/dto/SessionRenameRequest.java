package com.nl2sql.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 会话重命名请求体（{@code PATCH /api/v1/sessions/{id}}）。
 *
 * @see com.nl2sql.controller.SessionController
 */
@Data
@Schema(description = "会话重命名请求")
public class SessionRenameRequest {

    /** 新标题，不能为空白 */
    @NotBlank(message = "会话标题不能为空")
    @Size(max = 100, message = "会话标题最长 100 字")
    @Schema(description = "新标题", example = "各部门人数排查", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;
}
