package com.nl2sql.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录响应体。
 */
@Data
@AllArgsConstructor
@Schema(description = "登录响应")
public class LoginResponse {

    /** JWT Token */
    @Schema(description = "JWT 认证 Token")
    private String token;

    /** 用户名 */
    @Schema(description = "用户名")
    private String username;

    /** 用户角色 */
    @Schema(description = "用户角色")
    private String role;
}
