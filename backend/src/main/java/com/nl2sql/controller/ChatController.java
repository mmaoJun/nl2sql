package com.nl2sql.controller;

import com.nl2sql.common.BusinessException;
import com.nl2sql.common.ErrorCode;
import com.nl2sql.model.dto.ApiResponse;
import com.nl2sql.model.dto.ChatRequest;
import com.nl2sql.model.vo.ChatResult;
import com.nl2sql.model.vo.ChatStreamEvent;
import com.nl2sql.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import reactor.core.publisher.Flux;

/**
 * 聊天接口：同步问答 + SSE 流式。
 *
 * <p>契约（T12 前端按此对接）：
 * <ul>
 *   <li>{@code POST /api/v1/chat} — body {@code {question, datasourceId, sessionId?}}，
 *       返回 {@code ApiResponse<ChatResult>}；</li>
 *   <li>{@code GET /api/v1/chat/stream?question&datasourceId&sessionId} —
 *       {@code text/event-stream}，事件名 {@code TEXT_DELTA}（{@code {delta}}，
 *       本地通道可能缺失）/ {@code TOOL_CALL} / {@code TOOL_RESULT}（{@link com.nl2sql.model.vo.AgentTraceItem}）/
 *       {@code FINAL}（载荷同 {@link ChatResult}，必定送达）/ {@code ERROR}
 *       （{@code {code, message}}，发后终止）；正常结束即关闭连接。</li>
 * </ul>
 *
 * <p>校验（两个入口一致）：question 非空、datasourceId 非空，
 * 不合法抛 {@link BusinessException}（{@link ErrorCode#CHAT_INVALID_REQUEST}）；
 * 请求体缺失或 JSON 非法同样返回 4001（{@link #handleUnreadableBody}），不再落全局兜底的 500。
 * 认证由 T10 接入，会话由 T11 接入：sessionId 为 {@code chat_sessions.id}，
 * 非空走归属校验 + 历史注入，为空则自动新建会话（结果里回填 sessionId），
 * 类型不是数字时同样返回 4001（{@link #handleTypeMismatch}）。
 *
 * @see ChatService
 */
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatService chatService;

    /**
     * 同步问答：一次性返回完整结果（含轨迹与耗时）。
     *
     * @param request 聊天请求
     * @return 统一响应体包装的聊天结果
     */
    @PostMapping
    public ApiResponse<ChatResult> chat(@RequestBody ChatRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.CHAT_INVALID_REQUEST, "请求体不能为空");
        }
        validateArgs(request.getQuestion(), request.getDatasourceId());
        ChatResult result = chatService.ask(request.getDatasourceId(), request.getQuestion(), request.getSessionId());
        return ApiResponse.ok(result);
    }

    /**
     * 流式问答：SSE 事件流（TEXT_DELTA / TOOL_CALL / TOOL_RESULT / FINAL / ERROR）。
     *
     * <p>超时由 {@code spring.mvc.async.request-timeout}（5 分钟）兜底；
     * 客户端断开按框架默认处理（响应写失败即停止推送）。
     *
     * <p>会话不存在/不属于当前用户（3001）等起始阶段错误在流开始前抛出，
     * 按 {@link BusinessException} 语义返回 HTTP 200 + body 错误码。
     *
     * @param question     自然语言问题
     * @param datasourceId 数据源 ID
     * @param sessionId    会话 ID（{@code chat_sessions.id}），可空（为空时自动新建会话）
     * @return SSE 事件流
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(
            @RequestParam(value = "question", required = false) String question,
            @RequestParam(value = "datasourceId", required = false) Long datasourceId,
            @RequestParam(value = "sessionId", required = false) Long sessionId) {
        validateArgs(question, datasourceId);
        return chatService.stream(datasourceId, question, sessionId)
                .map(this::toServerSentEvent);
    }

    /** SSE 域事件 → 传输层事件（event 名直传，载荷由 Jackson 按运行时类型序列化）。 */
    private ServerSentEvent<Object> toServerSentEvent(ChatStreamEvent event) {
        return ServerSentEvent.builder()
                .event(event.event())
                .data(event.data())
                .build();
    }

    /** 两个入口共用的参数校验（BusinessException 语义，见类注释）。 */
    private void validateArgs(String question, Long datasourceId) {
        if (question == null || question.isBlank()) {
            throw new BusinessException(ErrorCode.CHAT_INVALID_REQUEST, "question 不能为空");
        }
        if (datasourceId == null) {
            throw new BusinessException(ErrorCode.CHAT_INVALID_REQUEST, "datasourceId 不能为空");
        }
    }

    /**
     * 请求体缺失或 JSON 非法：返回业务码 4001（与手工参数校验同一语义），
     * 不再落到全局兜底的 500/9999。
     *
     * <p>处理器挂在控制器内（而非 {@code @RestControllerAdvice}），让 4001 只作用于
     * chat 入口，不影响其他接口的错误码语义。无 {@code @ResponseStatus}，
     * 与 {@link BusinessException} 一致返回 HTTP 200 + body 错误码。
     *
     * @param ex 消息不可读异常
     * @return 4001 错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("[chat] 请求体缺失或 JSON 非法: {}", ex.getMessage());
        return ApiResponse.error(ErrorCode.CHAT_INVALID_REQUEST, "请求体不能为空或 JSON 格式非法");
    }

    /**
     * 查询参数类型不匹配（典型场景：sessionId 传了非数字，T9 时期该参数是字符串）。
     *
     * <p>与手工参数校验同一语义返回 4001，而不是落到全局兜底的 HTTP 500/9999。
     *
     * @param ex 类型不匹配异常
     * @return 4001 错误响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ApiResponse<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("[chat] 查询参数类型不匹配: name={} value={}", ex.getName(), ex.getValue());
        return ApiResponse.error(ErrorCode.CHAT_INVALID_REQUEST,
                "参数 " + ex.getName() + " 类型不合法");
    }
}
