package com.nl2sql.controller;

import com.nl2sql.common.BusinessException;
import com.nl2sql.common.ErrorCode;
import com.nl2sql.model.dto.ApiResponse;
import com.nl2sql.model.dto.SessionCreateRequest;
import com.nl2sql.model.dto.SessionRenameRequest;
import com.nl2sql.model.vo.SessionVO;
import com.nl2sql.model.vo.TurnVO;
import com.nl2sql.service.AuthService;
import com.nl2sql.service.ChatSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对话会话管理接口。
 *
 * <p>会话与轮次的读写入口。归属用户一律由 {@link #currentUserId} 从 JWT 解析，
 * 请求体与查询参数里都不存在 userId —— 让前端传 userId 等于把越权做成一个参数。
 *
 * <p>路径落在 {@code /api/v1/sessions}，被 {@code SecurityConfig} 的
 * {@code anyRequest().authenticated()} 覆盖，无需改安全配置。
 *
 * <p>错误语义：{@code BusinessException} 走全局处理器返回 HTTP 200 + body 错误码
 * （3001 会话不存在/不属于当前用户，401 未认证）；重命名的标题校验用 {@code @Valid}，
 * 走 {@code MethodArgumentNotValidException} 分支返回 HTTP 400（与旧实现一致）。
 *
 * @see ChatSessionService
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
@Tag(name = "对话会话", description = "会话与历史轮次管理")
public class SessionController {

    private final ChatSessionService sessionService;
    private final AuthService authService;

    /**
     * 新建会话。
     *
     * @param request        标题与默认数据源，均可空
     * @param authentication Spring Security 认证信息
     * @return 新建会话的视图
     */
    @PostMapping
    @Operation(summary = "新建会话", description = "标题可空，首轮提问后自动取问题前 30 字")
    public ApiResponse<SessionVO> create(@Valid @RequestBody SessionCreateRequest request,
                                         Authentication authentication) {
        return ApiResponse.ok(sessionService.create(currentUserId(authentication), request));
    }

    /**
     * 查当前用户的会话列表。
     *
     * @param authentication Spring Security 认证信息
     * @return 会话摘要列表，按最后活跃时间倒序，最多 50 条
     */
    @GetMapping
    @Operation(summary = "会话列表", description = "仅返回当前用户的会话，按最后活跃时间倒序")
    public ApiResponse<List<SessionVO>> list(Authentication authentication) {
        return ApiResponse.ok(sessionService.list(currentUserId(authentication)));
    }

    /**
     * 查会话的历史轮次，用于刷新页面后重建对话。
     *
     * @param id             会话 ID
     * @param authentication Spring Security 认证信息
     * @return 轮次列表，时间正序，最多最近 100 轮；不含结果行
     */
    @GetMapping("/{id}/turns")
    @Operation(summary = "会话轮次", description = "时间正序返回，不含结果行")
    public ApiResponse<List<TurnVO>> turns(@PathVariable Long id, Authentication authentication) {
        return ApiResponse.ok(sessionService.listTurns(id, currentUserId(authentication)));
    }

    /**
     * 重命名会话。
     *
     * @param id             会话 ID
     * @param request        新标题
     * @param authentication Spring Security 认证信息
     * @return 改名后的会话视图
     */
    @PatchMapping("/{id}")
    @Operation(summary = "重命名会话")
    public ApiResponse<SessionVO> rename(@PathVariable Long id,
                                         @Valid @RequestBody SessionRenameRequest request,
                                         Authentication authentication) {
        return ApiResponse.ok(sessionService.rename(id, currentUserId(authentication), request));
    }

    /**
     * 删除会话，级联删除其轮次；审计记录保留。
     *
     * @param id             会话 ID
     * @param authentication Spring Security 认证信息
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除会话", description = "级联删除轮次，审计记录保留")
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication authentication) {
        sessionService.delete(id, currentUserId(authentication));
        return ApiResponse.ok();
    }

    /**
     * 从认证信息解析当前用户 ID。
     *
     * <p>解析不出来（token 里的用户名在 users 表中已不存在）时报 401 而不是 3001：
     * 这是"你是谁"的问题，不是"这份数据在不在"的问题，混在一起会让前端把重新登录
     * 引导成"会话已删除"。
     *
     * @param authentication Spring Security 认证信息
     * @return 当前用户 ID
     * @throws BusinessException 未认证（401）
     */
    private Long currentUserId(Authentication authentication) {
        Long userId = authService.resolveUserId(
                authentication == null ? null : authentication.getName());
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未认证，无法使用会话功能");
        }
        return userId;
    }
}
