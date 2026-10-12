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
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 *
 * <p><b>审计</b>：每次问答（成功与异常终止）各落一条 {@code audit_logs} 记录。
 * 用户身份与模型在问答发起时（请求线程）解析——SSE 通道的订阅发生在请求线程之后，
 * 届时 {@code SecurityContextHolder} 的 ThreadLocal 已不可靠。
 *
 * @see AuditService
 */
@Service
@Slf4j
public class ChatService {

    /** 同步问答的整体超时（兜底；framework 侧 SSE 超时见 spring.mvc.async.request-timeout） */
    private static final Duration ASK_TIMEOUT = Duration.ofMinutes(5);

    /** 审计 generated_sql 列在“成功但本轮没跑过 SQL”时的说明文案 */
    private static final String NO_SQL_NOTE = "本轮未成功执行任何 SQL（模型未调用 execute_sql 或调用未成功）";

    /** 提取 SQL / 行数的工具名（与 {@link AgentEventCollector} 同口径） */
    private static final String EXECUTE_SQL_TOOL = "execute_sql";

    private final ReActAgent nl2sqlAgent;
    private final AuditService auditService;
    private final AuthService authService;

    /**
     * @param nl2sqlAgent  默认装配的 ReActAgent（本地 Ollama / 云端 DashScope 由配置决定）
     * @param auditService 审计日志服务（每次问答收尾写入）
     * @param authService  认证服务（SecurityContext 用户名 → userId 解析）
     */
    public ChatService(ReActAgent nl2sqlAgent, AuditService auditService, AuthService authService) {
        this.nl2sqlAgent = nl2sqlAgent;
        this.auditService = auditService;
        this.authService = authService;
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
        // 身份与模型在请求线程上解析（SSE 的异步订阅线程上 SecurityContext 不可用）
        Long userId = resolveCurrentUserId();
        String model = resolveModelName();
        String provider = resolveModelProvider();

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
            BusinessException translated = translateFailure("同步问答失败", e);
            auditTurn(userId, datasourceId, question, collector, model, provider,
                    null, translated.getMessage(), elapsedMs(startNanos));
            throw translated;
        }
        if (events == null) {
            BusinessException failure = new BusinessException(ErrorCode.CHAT_EXECUTION_FAILED, "Agent 事件流为空");
            auditTurn(userId, datasourceId, question, collector, model, provider,
                    null, failure.getMessage(), elapsedMs(startNanos));
            throw failure;
        }

        long latencyMs = elapsedMs(startNanos);
        ChatResult result = collector.toResult(model, latencyMs);
        auditTurn(userId, datasourceId, question, collector, model, provider, result, null, latencyMs);
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
        // 身份与模型在请求线程上解析（SSE 的异步订阅线程上 SecurityContext 不可用）
        Long userId = resolveCurrentUserId();
        String model = resolveModelName();
        String provider = resolveModelProvider();

        long startNanos = System.nanoTime();
        AgentEventCollector collector = new AgentEventCollector(startNanos);

        return nl2sqlAgent.streamEvents(wrapped, ctx)
                .concatMap(event -> Flux.fromIterable(collector.accept(event)))
                .concatWith(Flux.defer(() -> {
                    // FINAL 生成即本轮成功收尾：审计先落库再发事件（失败不阻塞回答，AuditService 不抛）
                    ChatResult result = collectResult(collector, model, startNanos);
                    auditTurn(userId, datasourceId, question, collector, model, provider, result, null,
                            result.getLatencyMs());
                    return Flux.just(new ChatStreamEvent(ChatStreamEvent.EVENT_FINAL, result));
                }))
                .onErrorResume(e -> {
                    BusinessException translated = translateFailure("流式问答中断", e);
                    auditTurn(userId, datasourceId, question, collector, model, provider,
                            null, translated.getMessage(), elapsedMs(startNanos));
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

    /**
     * 写入一条审计记录（成功与失败两条路径共用）。
     *
     * <p>字段口径（对齐旧项目 {@code AuditService.log} 的参数语义；SQL 校验与执行发生在
     * {@code execute_sql} 工具内部，这里记录可观测的端到端结果）：
     * <ul>
     *   <li>{@code userId} — SecurityContext 用户名经 {@link AuthService#resolveUserId} 解析；</li>
     *   <li>{@code generatedSql} — 本轮最后一次 execute_sql 入参中的 SQL（<b>不限成功</b>，
     *       被校验或执行拒绝的 SQL 同样留痕）；</li>
     *   <li>{@code validationPassed} — 本轮是否存在成功的 execute_sql 调用
     *       （工具内部先安全校验后执行，调用成功即校验已通过）；</li>
     *   <li>{@code executionSuccess} — 本轮问答是否正常完成（异常终止为 false）；</li>
     *   <li>{@code rowCount} — 成功路径下最后一次成功 execute_sql 的结果行数，失败为 null；</li>
     *   <li>{@code errorMessage} — 失败原因；成功但没跑成 SQL 时写 {@value #NO_SQL_NOTE}；</li>
     *   <li>{@code modelName} / {@code modelProvider} — 本轮真实模型与通道（ollama / dashscope）；</li>
     *   <li>{@code sessionId} / {@code turnId} — T11 接入前固定 null。</li>
     * </ul>
     *
     * @param result       成功路径的问答结果；失败路径传 null
     * @param errorMessage 失败原因；成功路径传 null
     * @param elapsedMs    本轮端到端耗时（毫秒）
     */
    private void auditTurn(Long userId, Long datasourceId, String question, AgentEventCollector collector,
                           String model, String provider, ChatResult result, String errorMessage,
                           long elapsedMs) {
        boolean sqlExecuted = result != null && hasSuccessfulExecuteSql(result);
        Integer rowCount = sqlExecuted ? result.getRowCount() : null;
        String message = errorMessage;
        if (message == null && !sqlExecuted) {
            message = NO_SQL_NOTE;
        }
        AuditContext context = new AuditContext(userId, sqlExecuted, result != null, rowCount,
                elapsedMs, message, model, provider, null, null);
        auditService.log(userId, datasourceId, question, collector.lastRequestedSql(), context);
    }

    /** 本轮是否存在成功的 execute_sql 工具调用（TOOL_RESULT 的 success=true）。 */
    private static boolean hasSuccessfulExecuteSql(ChatResult result) {
        return result.getTrace().stream()
                .anyMatch(item -> AgentTraceItem.TYPE_TOOL_RESULT.equals(item.type())
                        && EXECUTE_SQL_TOOL.equals(item.tool())
                        && Boolean.TRUE.equals(item.success()));
    }

    /** 汇总本轮采集结果（含成功日志），供 SSE FINAL 事件与审计共用。 */
    private ChatResult collectResult(AgentEventCollector collector, String model, long startNanos) {
        long latencyMs = elapsedMs(startNanos);
        ChatResult result = collector.toResult(model, latencyMs);
        log.info("[chat] 流式问答完成: latencyMs={} firstEventMs={} rows={} tools={}",
                latencyMs, result.getFirstEventMs(), result.getRowCount(), toolNames(result));
        return result;
    }

    /**
     * 本轮实际使用的模型名（取自默认 Agent 的模型 Bean）。
     *
     * <p>在请求线程上解析（模型 Bean 与线程无关，解析失败返回 {@code unknown}）。
     */
    private String resolveModelName() {
        try {
            Model model = nl2sqlAgent.getModel();
            return model != null && model.getModelName() != null ? model.getModelName() : "unknown";
        } catch (Exception e) {
            log.warn("[chat] 读取模型名失败: {}", e.getMessage());
            return "unknown";
        }
    }

    /**
     * 本轮实际使用的模型提供方，取值与旧项目对齐：{@code ollama}（本地通道）/
     * {@code dashscope}（云端 DashScope 通道）。
     *
     * @return 提供方；模型 Bean 不可用时为 null（审计列为空，不写脏值）
     */
    private String resolveModelProvider() {
        try {
            Model model = nl2sqlAgent.getModel();
            if (model == null) {
                return null;
            }
            return model instanceof DashScopeChatModel ? "dashscope" : "ollama";
        } catch (Exception e) {
            log.warn("[chat] 读取模型提供方失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 当前请求的用户 ID：SecurityContext 用户名经 {@link AuthService#resolveUserId} 解析。
     *
     * <p>必须在请求线程上调用：SSE 通道返回 Flux 后订阅发生在异步线程，
     * {@code SecurityContextHolder} 的 ThreadLocal 不随之传播。
     *
     * @return 用户 ID；未认证（匿名）或用户名解析不到时返回 null（审计列为空）
     */
    private Long resolveCurrentUserId() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null) {
                return null;
            }
            return authService.resolveUserId(authentication.getName());
        } catch (Exception e) {
            log.warn("[chat] 解析当前用户失败，审计 userId 置空: {}", e.getMessage());
            return null;
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
