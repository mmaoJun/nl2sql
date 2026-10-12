package com.nl2sql.controller;

import com.nl2sql.model.dto.ApiResponse;
import com.nl2sql.model.dto.LoginRequest;
import com.nl2sql.model.dto.LoginResponse;
import com.nl2sql.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器。
 *
 * <p>提供用户注册与登录接口，登录成功后返回 JWT Token。
 * 所有接口均在 Spring Security 白名单中，无需认证即可访问。</p>
 *
 * @see AuthService
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "认证管理", description = "用户注册与登录")
public class AuthController {

    private final AuthService authService;

    /**
     * 用户注册。
     *
     * <p>用户名不可重复，密码使用 BCrypt 加密存储，默认角色为 USER。</p>
     *
     * @param request 注册请求，包含用户名和密码
     * @return 注册成功提示信息
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册", description = "注册新用户，用户名不可重复")
    public ApiResponse<String> register(@Valid @RequestBody LoginRequest request) {
        authService.register(request);
        return ApiResponse.ok("注册成功");
    }

    /**
     * 用户登录。
     *
     * <p>校验用户名和密码，成功后生成 JWT Token 返回。Token 有效期由 jwt.expiration 配置。</p>
     *
     * @param request 登录请求，包含用户名和密码
     * @return 包含 JWT Token、用户名、角色的登录响应
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "验证用户名密码，返回 JWT Token")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }
}
