package com.nl2sql.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 响应体。
 *
 * <p>所有接口返回格式为 {@code {code, message, data}}，
 * 成功时 code=200，失败时 code 为业务错误码。
 *
 * @param <T> 响应数据类型
 * @see com.nl2sql.config.GlobalExceptionHandler
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ApiResponse<T> {

    /** 响应码，200 表示成功 */
    private int code;

    /** 响应消息 */
    private String message;

    /** 响应数据 */
    private T data;

    /**
     * 成功响应（携带数据）。
     *
     * @param data 响应数据
     * @param <T>  数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    /**
     * 成功响应（无数据）。
     *
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(200, "success", null);
    }

    /**
     * 错误响应（指定错误码）。
     *
     * @param code    错误码
     * @param message 错误消息
     * @param <T>     数据类型
     * @return 错误响应
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    /**
     * 错误响应（默认 500 错误码）。
     *
     * @param message 错误消息
     * @param <T>     数据类型
     * @return 错误响应
     */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(500, message, null);
    }
}
