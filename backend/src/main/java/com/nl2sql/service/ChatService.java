package com.nl2sql.service;

import com.nl2sql.common.BusinessException;
import com.nl2sql.common.ErrorCode;
import com.nl2sql.model.vo.AgentTraceItem;
import com.nl2sql.model.vo.ChatResult;
import com.nl2sql.model.vo.ChatStreamEvent;
import com.nl2sql.model.vo.ErrorPayload;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 聊天服务：把默认装配的 {@link ReActAgent} 暴露为“提问 → 采集 → {@link ChatResult}”。
 *
 * <p>两条通道共用同一套采集口径（{@link AgentEventCollector}）：
 * <ul>
 *   <li>{@link #ask}（同步）— {@code collectList().block(5min)} 收全事件后汇总，供
 *       {@code POST /api/v1/chat} 使用；</li>
 *   <li>{@link #stream}（流式）— 事件边到边映射为 {@link ChatStreamEvent}，结束时追加
 *       {@code FINAL}，出错时发 {@code ERROR} 终止，供 {@code GET /api/v1/chat/stream} 使用。</li>
 * </ul>
 *
 * <p>问题按工具与提示词约定包装为 {@code "数据源ID为 N。" + question}；
 * 每次调用用独立 sessionId（外部传入或随机生成）隔离会话状态。
 */
@Service
@Slf4j
public class ChatService {

    /** 同步问答的整体超时（兜底；framework 侧 SSE 超时见 spring.mvc.async.request-timeout） */
    private static final Duration ASK_TIMEOUT = Duration.ofMinutes(5);

    private final ReActAgent nl2sqlAgent;

    /**
     * @param nl2sqlAgent 默认装配的 ReActAgent（本地 Ollama / 云端 DashScope 由配置决定）
     */
    public ChatService(ReActAgent nl2sqlAgent) {
        this.nl2sqlAgent = nl2sqlAgent;
    }

    /**
     * 同步问答：收集全量事件后汇总为结果。
     *
     * @param datasourceId    数据源 ID
     * @param question        用户问题（自动包装“数据源ID为 N。”前缀）
     * @param sessionIdOrNull 会话 ID，可空；当前仅用于隔离 Agent 会话，尚未注入历史
     * @return 聊天结果（answer / sql / columns / rows / trace / 耗时等）
     * @throws BusinessException Agent 执行失败或超时（错误码 {@link ErrorCode#CHAT_EXECUTION_FAILED}）
     */
    public ChatResult ask(Long datasourceId, String question, String sessionIdOrNull) {
        // T11: 注入历史（本轮忽略 sessionIdOrNull 中的历史语义，仅作会话隔离）
        String wrapped = wrapQuestion(datasourceId, question);
        RuntimeContext ctx = buildContext(sessionIdOrNull);

        long startNanos = System.nanoTime();
        AgentEventCollector collector = new AgentEventCollector(startNanos);
        List<AgentEvent> events;
        try {
            // 事件边到边喂采集器（与 stream 路径同机制），firstEventMs 才是真实的“首个事件到达”耗时
            events = nl2sqlAgent.streamEvents(wrapped, ctx)
                    .doOnNext(collector::accept)
                    .collectList()
                    .block(ASK_TIMEOUT);
        } catch (RuntimeException e) {
            throw translateFailure("同步问答失败", e);
        }
        if (events == null) {
            throw new BusinessException(ErrorCode.CHAT_EXECUTION_FAILED, "Agent 事件流为空");
        }

        long latencyMs = elapsedMs(startNanos);
        ChatResult result = collector.toResult(resolveModelName(), latencyMs);
        if (result.getAnswer() == null || result.getAnswer().isBlank()) {
            log.warn("[chat] 本轮最终回答为空: datasourceId={} 工具序列={} 耗时={}ms",
                    datasourceId, toolNames(result), latencyMs);
        }
        log.info("[chat] 同步问答完成: datasourceId={} latencyMs={} firstEventMs={} rows={} tools={}",
                datasourceId, latencyMs, result.getFirstEventMs(), result.getRowCount(), toolNames(result));
        return result;
    }

    /**
     * 流式问答：事件边到边映射为 SSE 域事件，正常以 {@code FINAL} 收尾，异常发 {@code ERROR} 后终止。
     *
     * @param datasourceId    数据源 ID
     * @param question        用户问题（自动包装“数据源ID为 N。”前缀）
     * @param sessionIdOrNull 会话 ID，可空；当前仅用于隔离 Agent 会话，尚未注入历史
     * @return 事件流（TEXT_DELTA / TOOL_CALL / TOOL_RESULT / FINAL / ERROR）
     */
    public Flux<ChatStreamEvent> stream(Long datasourceId, String question, String sessionIdOrNull) {
        // T11: 注入历史（本轮忽略 sessionIdOrNull 中的历史语义，仅作会话隔离）
        String wrapped = wrapQuestion(datasourceId, question);
        RuntimeContext ctx = buildContext(sessionIdOrNull);

        long startNanos = System.nanoTime();
        AgentEventCollector collector = new AgentEventCollector(startNanos);
        String model = resolveModelName();

        return nl2sqlAgent.streamEvents(wrapped, ctx)
                .concatMap(event -> Flux.fromIterable(collector.accept(event)))
                .concatWith(Flux.defer(() -> Flux.just(finalEvent(collector, model, startNanos))))
                .onErrorResume(e -> {
                    BusinessException translated = translateFailure("流式问答中断", e);
                    return Flux.just(new ChatStreamEvent(ChatStreamEvent.EVENT_ERROR,
                            new ErrorPayload(translated.getCode(), translated.getMessage())));
                });
    }

    /** 包装问题：满足工具与提示词的“数据源ID为 N”约定。 */
    private String wrapQuestion(Long datasourceId, String question) {
        return "数据源ID为 " + datasourceId + "。" + question;
    }

    /** 会话上下文：外部未传 sessionId 时生成随机值，隔离每轮问答的 Agent 状态。 */
    private RuntimeContext buildContext(String sessionIdOrNull) {
        String sessionId = (sessionIdOrNull == null || sessionIdOrNull.isBlank())
                ? "chat-" + UUID.randomUUID()
                : sessionIdOrNull;
        return RuntimeContext.builder().sessionId(sessionId).build();
    }

    /** 流结束时的 FINAL 事件（含完整 ChatResult 与耗时口径）。 */
    private ChatStreamEvent finalEvent(AgentEventCollector collector, String model, long startNanos) {
        long latencyMs = elapsedMs(startNanos);
        ChatResult result = collector.toResult(model, latencyMs);
        log.info("[chat] 流式问答完成: latencyMs={} firstEventMs={} rows={} tools={}",
                latencyMs, result.getFirstEventMs(), result.getRowCount(), toolNames(result));
        return new ChatStreamEvent(ChatStreamEvent.EVENT_FINAL, result);
    }

    /** 本轮实际使用的模型名（取自默认 Agent 的模型 Bean）。 */
    private String resolveModelName() {
        try {
            Model model = nl2sqlAgent.getModel();
            return model != null && model.getModelName() != null ? model.getModelName() : "unknown";
        } catch (Exception e) {
            log.warn("[chat] 读取模型名失败: {}", e.getMessage());
            return "unknown";
        }
    }

    /** 统一翻译 Agent 执行异常为业务异常（保留最底层原因，便于定位模型服务问题）。 */
    private BusinessException translateFailure(String scene, Throwable e) {
        log.error("[chat] {}: {}", scene, e.getMessage(), e);
        return new BusinessException(ErrorCode.CHAT_EXECUTION_FAILED,
                scene + ": " + rootMessage(e));
    }

    /** 取异常链最深处消息（WebClient/超时类异常外层消息不含根因）。 */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        return message != null ? message : cur.getClass().getSimpleName();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 轨迹中的工具名序列（日志用）。 */
    private static List<String> toolNames(ChatResult result) {
        return result.getTrace().stream()
                .filter(item -> AgentTraceItem.TYPE_TOOL_CALL.equals(item.type()))
                .map(AgentTraceItem::tool)
                .toList();
    }
}
