package com.nl2sql.common;

import lombok.Getter;

/**
 * 统一业务异常。
 *
 * <p>携带整数错误码和可读消息，由 {@link com.nl2sql.config.GlobalExceptionHandler}
 * 统一捕获后返回 HTTP 200 + body 错误码的 {@link com.nl2sql.model.dto.ApiResponse}。
 *
 * @see ErrorCode
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    /**
     * 构造业务异常。
     *
     * @param code    错误码，参见 {@link ErrorCode}
     * @param message 可读错误消息
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 构造业务异常（携带原始异常）。
     *
     * @param code    错误码
     * @param message 可读错误消息
     * @param cause   原始异常
     */
    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
