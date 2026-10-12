package com.nl2sql.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.common.BusinessException;
import com.nl2sql.common.ErrorCode;
import com.nl2sql.mapper.UserMapper;
import com.nl2sql.model.User;
import com.nl2sql.model.dto.LoginRequest;
import com.nl2sql.model.dto.LoginResponse;
import com.nl2sql.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证服务。
 *
 * <p>提供用户注册（BCrypt 加密）、登录（JWT Token 签发）和用户 ID 解析功能。
 *
 * @see JwtTokenProvider
 * @see com.nl2sql.model.dto.LoginRequest
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 用户注册。
     *
     * <p>校验用户名唯一性后，使用 BCrypt 加密密码并写入数据库，默认角色为 USER。
     *
     * @param request 注册请求（用户名 + 密码）
     * @throws BusinessException 用户名已存在时抛出
     */
    public void register(LoginRequest request) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));
        if (count > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名已存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole("USER");
        userMapper.insert(user);
        log.info("用户注册: username={}", request.getUsername());
    }

    /**
     * 用户登录。
     *
     * <p>校验用户名和密码后签发 JWT Token，Token 中携带用户名和角色信息。
     *
     * @param request 登录请求（用户名 + 密码）
     * @return 包含 Token、用户名和角色的登录响应
     * @throws BusinessException 用户名或密码错误时抛出
     */
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        String token = jwtTokenProvider.generateToken(user.getUsername(), user.getRole());
        return new LoginResponse(token, user.getUsername(), user.getRole());
    }

    /**
     * 根据用户名解析用户 ID，供审计日志等内部模块使用。
     *
     * @param username 用户名，允许为 null
     * @return 用户 ID；用户不存在或用户名为 null 时返回 null
     */
    public Long resolveUserId(String username) {
        if (username == null) {
            return null;
        }
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        return user != null ? user.getId() : null;
    }
}
