package com.nl2sql.model.vo;

/**
 * SSE {@code ERROR} 事件载荷：流式过程中出错时的终止通知（该事件之后流即结束）。
 *
 * @param code    错误码，参见 {@link com.nl2sql.common.ErrorCode}
 * @param message 可读错误消息
 */
public record ErrorPayload(int code, String message) {
}
