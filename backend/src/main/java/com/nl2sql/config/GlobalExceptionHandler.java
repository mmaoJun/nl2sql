package com.nl2sql.config;

import com.nl2sql.common.BusinessException;
import com.nl2sql.common.ErrorCode;
import com.nl2sql.model.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>统一捕获四类异常并转换为 {@link ApiResponse}：
 * <ul>
 *   <li>{@link BusinessException} → HTTP 200 + body 错误码</li>
 *   <li>{@link MethodArgumentNotValidException} → HTTP 400 + 字段校验消息</li>
 *   <li>{@link IllegalArgumentException} → HTTP 400</li>
 *   <li>{@link Exception} → HTTP 500 + 通用提示</li>
 * </ul>
 *
 * @see BusinessException
 * @see ErrorCode
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 处理业务异常，返回 HTTP 200 + body 错误码。
     *
     * @param ex 业务异常
     * @return 包含错误码和消息的响应体
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleBusinessException(BusinessException ex) {
        log.warn("业务异常: code={}, message={}", ex.getCode(), ex.getMessage());
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }

    /**
     * 处理参数校验异常，拼接所有字段错误消息。
     *
     * @param ex 校验异常
     * @return HTTP 400 + 校验错误消息
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return ApiResponse.error(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 处理非法参数异常。
     *
     * @param ex 非法参数异常
     * @return HTTP 400 + 错误消息
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.error(ErrorCode.BAD_REQUEST, ex.getMessage());
    }

    /**
     * 兜底处理所有未捕获异常，避免泄露内部错误细节。
     *
     * @param ex 未处理异常
     * @return HTTP 500 + 通用错误提示
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception ex) {
        log.error("未处理异常: {}", ex.getMessage(), ex);
        return ApiResponse.error(ErrorCode.SYSTEM_ERROR, "服务器内部错误");
    }
}
