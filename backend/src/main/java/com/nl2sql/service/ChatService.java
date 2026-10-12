package com.nl2sql.service;

import com.nl2sql.common.BusinessException;
import com.nl2sql.common.ErrorCode;
import com.nl2sql.common.TurnState;
import com.nl2sql.config.MemoryProperties;
import com.nl2sql.model.ChatTurn;
import com.nl2sql.model.dto.SessionCreateRequest;
import com.nl2sql.model.vo.AgentTraceItem;
import com.nl2sql.model.vo.ChatResult;
import com.nl2sql.model.vo.ChatStreamEvent;
import com.nl2sql.model.vo.ErrorPayload;
import com.nl2sql.model.vo.SessionVO;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
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
 * 每轮问答使用独立的 Agent 运行时会话（隔离 Agent 状态，见 {@link #buildContext()}）。
 *
 * <p><b>会话记忆</b>（T11）：sessionId 非空时校验归属并注入历史，
 * 为空则自动新建会话（{@code ChatResult.sessionId} 回填给前端）。历史 = 同会话
 * <b>同数据源</b>最近 {@code nl2sql.memory.context-turns} 轮的「用户问 + 助手答」消息对
 * + 当前包装问题，交给 {@code agent.streamEvents(List<Msg>, ctx)}。
 * 每轮问答（成功 / 失败 / 空回答）都往 {@code chat_turns} 落一行并推进会话活跃时间。
 *
 * <p><b>审计</b>：每次问答（成功与异常终止）各落一条 {@code audit_logs} 记录，
 * 携带会话与轮次归因（{@code session_id}/{@code turn_id}）。
 * 用户身份、模型与历史都在问答发起时（请求线程）解析——SSE 通道的订阅发生在请求线程之后，
 * 届时 {@code SecurityContextHolder} 的 ThreadLocal 已不可靠。
 *
 * @see ChatSessionService
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

    /** {@code chat_turns.error_message} 列宽（VARCHAR(500)），超长截断避免落库失败 */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    private final ReActAgent nl2sqlAgent;
    private final AuditService auditService;
    private final AuthService authService;
    private final ChatSessionService sessionService;
    private final MemoryProperties memoryProperties;

    /**
     * @param nl2sqlAgent      默认装配的 ReActAgent（本地 Ollama / 云端 DashScope 由配置决定）
     * @param auditService     审计日志服务（每次问答收尾写入）
     * @param authService      认证服务（SecurityContext 用户名 → userId 解析）
     * @param sessionService   会话服务（归属校验 / 历史读取 / 轮次落库）
     * @param memoryProperties 会话记忆配置（上下文窗口轮数）
     */
    public ChatService(ReActAgent nl2sqlAgent, AuditService auditService, AuthService authService,
                       ChatSessionService sessionService, MemoryProperties memoryProperties) {
        this.nl2sqlAgent = nl2sqlAgent;
        this.auditService = auditService;
        this.authService = authService;
        this.sessionService = sessionService;
        this.memoryProperties = memoryProperties;
    }

    /**
     * 同步问答：收集全量事件后汇总为结果。
     *
     * @param datasourceId    数据源 ID
     * @param question        用户问题（自动包装“数据源ID为 N。”前缀）
     * @param sessionIdOrNull 会话 ID，可空；非空时校验归属并注入历史，为空时自动新建会话
     * @return 聊天结果（answer / sessionId / sql / columns / rows / trace / 耗时等）
     * @throws BusinessException 会话不可用（3001 / 401）或 Agent 执行失败、超时
     *                           （错误码 {@link ErrorCode#CHAT_EXECUTION_FAILED}）
     */
    public ChatResult ask(Long datasourceId, String question, Long sessionIdOrNull) {
        Long userId = resolveCurrentUserId();
        Long sessionId = openSession(sessionIdOrNull, userId, datasourceId);
        String wrapped = wrapQuestion(datasourceId, question);
        List<Msg> messages = buildMessages(datasourceId, sessionId, wrapped);
        // 身份与模型在请求线程上解析（SSE 的异步订阅线程上 SecurityContext 不可用）
        String model = resolveModelName();
        String provider = resolveModelProvider();

        long startNanos = System.nanoTime();
        AgentEventCollector collector = new AgentEventCollector(startNanos);
        List<AgentEvent> events;
        try {
            // 事件边到边喂采集器（与 stream 路径同机制），firstEventMs 才是真实的“首个事件到达”耗时
            events = nl2sqlAgent.streamEvents(messages, buildContext())
                    .doOnNext(collector::accept)
                    .collectList()
                    .block(ASK_TIMEOUT);
        } catch (RuntimeException e) {
            BusinessException translated = translateFailure("同步问答失败", e);
            long elapsedMs = elapsedMs(startNanos);
            Long turnId = recordTurn(new TurnRecord(sessionId, question, datasourceId, model, provider,
                    collector.lastRequestedSql(), null, elapsedMs, TurnState.TURN_FAILED,
                    translated.getMessage(), null));
            auditTurn(userId, datasourceId, question, sessionId, turnId, collector, model, provider,
                    null, translated.getMessage(), elapsedMs);
            throw translated;
        }
        if (events == null) {
            BusinessException failure = new BusinessException(ErrorCode.CHAT_EXECUTION_FAILED, "Agent 事件流为空");
            long elapsedMs = elapsedMs(startNanos);
            Long turnId = recordTurn(new TurnRecord(sessionId, question, datasourceId, model, provider,
                    collector.lastRequestedSql(), null, elapsedMs, TurnState.TURN_FAILED,
                    failure.getMessage(), null));
            auditTurn(userId, datasourceId, question, sessionId, turnId, collector, model, provider,
                    null, failure.getMessage(), elapsedMs);
            throw failure;
        }

        long latencyMs = elapsedMs(startNanos);
        ChatResult result = collector.toResult(model, latencyMs);
        result.setSessionId(sessionId);
        String status = statusOf(result);
        Long turnId = recordTurn(new TurnRecord(sessionId, question, datasourceId, model, provider,
                result.getSql(), result.getRowCount(), latencyMs, status, null, result.getAnswer()));
        auditTurn(userId, datasourceId, question, sessionId, turnId, collector, model, provider,
                result, null, latencyMs);
        if (TurnState.TURN_EMPTY.equals(status)) {
            log.warn("[chat] 本轮最终回答为空: datasourceId={} sessionId={} 工具序列={} 耗时={}ms",
                    datasourceId, sessionId, toolNames(result), latencyMs);
        }
        log.info("[chat] 同步问答完成: datasourceId={} sessionId={} turnId={} latencyMs={} firstEventMs={} rows={} tools={}",
                datasourceId, sessionId, turnId, latencyMs, result.getFirstEventMs(),
                result.getRowCount(), toolNames(result));
        return result;
    }

    /**
     * 流式问答：事件边到边映射为 SSE 域事件，正常以 {@code FINAL} 收尾，异常发 {@code ERROR} 后终止。
     *
     * <p>会话/轮次与审计都在 FINAL 事件发出<b>之前</b>落库（同线程、先落库后发事件），
     * 因此客户端收到 FINAL 时 {@code chat_turns} 已可查。
     *
     * @param datasourceId    数据源 ID
     * @param question        用户问题（自动包装“数据源ID为 N。”前缀）
     * @param sessionIdOrNull 会话 ID，可空；非空时校验归属并注入历史，为空时自动新建会话
     * @return 事件流（TEXT_DELTA / TOOL_CALL / TOOL_RESULT / FINAL / ERROR）
     * @throws BusinessException 会话不可用（3001 / 401），在流开始前抛出
     */
    public Flux<ChatStreamEvent> stream(Long datasourceId, String question, Long sessionIdOrNull) {
        Long userId = resolveCurrentUserId();
        Long sessionId = openSession(sessionIdOrNull, userId, datasourceId);
        String wrapped = wrapQuestion(datasourceId, question);
        List<Msg> messages = buildMessages(datasourceId, sessionId, wrapped);
        // 身份与模型在请求线程上解析（SSE 的异步订阅线程上 SecurityContext 不可用）
        String model = resolveModelName();
        String provider = resolveModelProvider();

        long startNanos = System.nanoTime();
        AgentEventCollector collector = new AgentEventCollector(startNanos);

        return nl2sqlAgent.streamEvents(messages, buildContext())
                .concatMap(event -> Flux.fromIterable(collector.accept(event)))
                .concatWith(Flux.defer(() -> {
                    // FINAL 生成即本轮成功收尾：轮次与审计先落库再发事件（失败不阻塞回答）
                    ChatResult result = collectResult(collector, model, startNanos);
                    result.setSessionId(sessionId);
                    String status = statusOf(result);
                    Long turnId = recordTurn(new TurnRecord(sessionId, question, datasourceId, model, provider,
                            result.getSql(), result.getRowCount(), result.getLatencyMs(), status, null,
                            result.getAnswer()));
                    auditTurn(userId, datasourceId, question, sessionId, turnId, collector, model, provider,
                            result, null, result.getLatencyMs());
                    return Flux.just(new ChatStreamEvent(ChatStreamEvent.EVENT_FINAL, result));
                }))
                .onErrorResume(e -> {
                    BusinessException translated = translateFailure("流式问答中断", e);
                    long elapsedMs = elapsedMs(startNanos);
                    Long turnId = recordTurn(new TurnRecord(sessionId, question, datasourceId, model, provider,
                            collector.lastRequestedSql(), null, elapsedMs, TurnState.TURN_FAILED,
                            translated.getMessage(), null));
                    auditTurn(userId, datasourceId, question, sessionId, turnId, collector, model, provider,
                            null, translated.getMessage(), elapsedMs);
                    return Flux.just(new ChatStreamEvent(ChatStreamEvent.EVENT_ERROR,
                            new ErrorPayload(translated.getCode(), translated.getMessage())));
                });
    }

    /** 包装问题：满足工具与提示词的“数据源ID为 N”约定。 */
    private String wrapQuestion(Long datasourceId, String question) {
        return "数据源ID为 " + datasourceId + "。" + question;
    }

    /**
     * 解析本轮会话：非空 → 校验归属；空 → 自动新建会话。
     *
     * <p>归属校验<b>不做降级</b>（会话不是你的，就不该借着它的历史查数据）；
     * 自动建会话失败则降级为无会话模式（本轮不落轮次、结果里 sessionId 为空）——
     * 记忆是增强不是前置依赖，不能让一次记账失败把本来能成功的查询变成失败。
     *
     * @param requestedSessionId 请求携带的会话 ID，可为 null
     * @param userId             当前用户 ID，未认证为 null
     * @param datasourceId       本轮数据源（作为新建会话的默认数据源）
     * @return 本轮会话 ID；无会话模式为 null
     * @throws BusinessException 未认证（401）或会话不存在/不属于当前用户（3001）
     */
    private Long openSession(Long requestedSessionId, Long userId, Long datasourceId) {
        if (requestedSessionId != null) {
            sessionService.requireOwned(requestedSessionId, userId);
            return requestedSessionId;
        }
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未认证，无法使用会话功能");
        }
        try {
            SessionCreateRequest request = new SessionCreateRequest();
            request.setDatasourceId(datasourceId);
            SessionVO created = sessionService.create(userId, request);
            log.info("[chat] 自动新建会话: sessionId={} userId={} datasourceId={}",
                    created.getId(), userId, datasourceId);
            return created.getId();
        } catch (Exception e) {
            log.warn("[chat] 自动新建会话失败，本轮按无会话模式处理: userId={} datasourceId={}",
                    userId, datasourceId, e);
            return null;
        }
    }

    /**
     * 组装交给 Agent 的消息序列：历史「用户问 + 助手答」消息对 + 当前包装问题。
     *
     * <p>历史按<b>数据源隔离</b>（见 {@link ChatSessionService#recentTurns}）：换库后第一轮
     * 取到空上下文，等于该库下的首轮。历史中的用户消息同样带“数据源ID为 N。”
     * 包装，与当前问题保持同一种格式，避免模型把历史消息当成另一种协议。
     *
     * @param datasourceId    本轮数据源
     * @param sessionId       本轮会话，null 表示无会话模式（无历史）
     * @param wrappedQuestion 当前包装问题
     * @return 消息序列（末尾必为当前用户问题）
     */
    private List<Msg> buildMessages(Long datasourceId, Long sessionId, String wrappedQuestion) {
        List<Msg> messages = new ArrayList<>();
        if (sessionId != null) {
            for (ChatTurn turn : loadHistory(sessionId, datasourceId)) {
                messages.add(textMessage(MsgRole.USER, wrapQuestion(datasourceId, turn.getQuestion())));
                messages.add(textMessage(MsgRole.ASSISTANT, turn.getAnswer()));
            }
        }
        messages.add(textMessage(MsgRole.USER, wrappedQuestion));
        return messages;
    }

    /**
     * 加载可注入的历史轮次：同会话同数据源最近 N 轮，过滤掉没有助手回答的轮次。
     *
     * <p>跳过条件：{@code question} 或 {@code answer} 为空。失败与空回答的轮次没有助手文本，
     * 而空 assistant 消息既可能被模型服务判为非法，也会让"问-答成对"的语义落空。
     * 历史读取失败只降级（当无历史），不影响本轮问答。
     *
     * @param sessionId    会话 ID
     * @param datasourceId 数据源 ID
     * @return 历史轮次（时间正序），可能为空
     */
    private List<ChatTurn> loadHistory(Long sessionId, Long datasourceId) {
        List<ChatTurn> history;
        try {
            history = sessionService.recentTurns(sessionId, datasourceId, memoryProperties.getContextTurns());
        } catch (Exception e) {
            log.warn("[chat] 历史轮次加载失败，本轮按无历史处理: sessionId={} datasourceId={}",
                    sessionId, datasourceId, e);
            return List.of();
        }
        List<ChatTurn> usable = history.stream()
                .filter(turn -> !isBlank(turn.getQuestion()) && !isBlank(turn.getAnswer()))
                .toList();
        if (usable.size() < history.size()) {
            log.info("[chat] 历史轮次中 {} 轮无助手回答，未注入: sessionId={}",
                    history.size() - usable.size(), sessionId);
        }
        return usable;
    }

    /** 构造一条纯文本消息。 */
    private static Msg textMessage(MsgRole role, String text) {
        return Msg.builder().role(role).textContent(text).build();
    }

    /**
     * 本轮 Agent 运行时上下文：每轮问答独立 sessionId。
     *
     * <p>不使用 AgentScope 自带的会话状态记忆：它是进程内内存态（重启即失）、无法按数据源
     * 隔离，且与我们从 {@code chat_turns} 注入的历史叠在一起会变成双份记忆。
     * 多轮上下文的唯一来源是数据库轮次表，Agent 本身保持无状态。
     */
    private RuntimeContext buildContext() {
        return RuntimeContext.builder().sessionId("chat-" + UUID.randomUUID()).build();
    }

    /** 本轮结果的轮次状态：回答为空记 {@code empty}，否则 {@code ok}。 */
    private static String statusOf(ChatResult result) {
        return isBlank(result.getAnswer()) ? TurnState.TURN_EMPTY : TurnState.TURN_OK;
    }

    /**
     * 写入一轮对话（成功 / 失败 / 空回答都落）并推进会话活跃时间。
     *
     * <p>失败只 warn 并返回 null，<b>绝不抛异常</b>：用户已经拿到查询结果了，
     * 不能因为记账失败把成功响应变成 500（同步路径落库在返回结果前、流式路径落库在 FINAL 前，
     * 异常外溢都会破坏响应）。无会话模式（sessionId 为 null）一行都不写。
     *
     * <p>标题与活跃时间在同一次调用里推进：{@code applyFirstQuestionTitle} 是带条件的
     * UPDATE（仅当标题仍为占位值时生效），所以对后续轮次是空操作，不会覆盖用户改名。
     * {@code resolved_question} / {@code rewrite_status} 不写值（本项目不做改写）。
     *
     * @param record 本轮结果
     * @return 轮次 ID；无会话模式或写入失败时为 null
     */
    private Long recordTurn(TurnRecord record) {
        if (record.sessionId() == null) {
            return null;
        }
        try {
            ChatTurn turn = new ChatTurn();
            turn.setSessionId(record.sessionId());
            turn.setQuestion(record.question());
            turn.setDatasourceId(record.datasourceId());
            turn.setModelName(record.modelName());
            turn.setModelProvider(record.modelProvider());
            turn.setGeneratedSql(record.generatedSql());
            turn.setRowCount(record.rowCount());
            turn.setExecutionTimeMs(record.executionTimeMs());
            turn.setStatus(record.status());
            turn.setErrorMessage(truncate(record.errorMessage(), ERROR_MESSAGE_MAX_LENGTH));
            turn.setAnswer(record.answer());
            Long turnId = sessionService.insertTurn(turn);
            sessionService.applyFirstQuestionTitle(record.sessionId(), record.question());
            sessionService.touch(record.sessionId());
            return turnId;
        } catch (Exception e) {
            log.warn("[chat] 会话轮次写入失败，本次问答结果不受影响: sessionId={} question={}",
                    record.sessionId(), record.question(), e);
            return null;
        }
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
     *   <li>{@code sessionId} / {@code turnId} — T11 起的会话归因（轮次写入失败时 turnId 为 null）。</li>
     * </ul>
     *
     * @param sessionId    本轮会话 ID，无会话模式为 null
     * @param turnId       本轮轮次 ID，无会话模式或落库失败为 null
     * @param result       成功路径的问答结果；失败路径传 null
     * @param errorMessage 失败原因；成功路径传 null
     * @param elapsedMs    本轮端到端耗时（毫秒）
     */
    private void auditTurn(Long userId, Long datasourceId, String question, Long sessionId, Long turnId,
                           AgentEventCollector collector, String model, String provider,
                           ChatResult result, String errorMessage, long elapsedMs) {
        boolean sqlExecuted = result != null && hasSuccessfulExecuteSql(result);
        Integer rowCount = sqlExecuted ? result.getRowCount() : null;
        String message = errorMessage;
        if (message == null && !sqlExecuted) {
            message = NO_SQL_NOTE;
        }
        AuditContext context = new AuditContext(userId, sqlExecuted, result != null, rowCount,
                elapsedMs, message, model, provider, sessionId, turnId);
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

    /** 文本是否为空（null / 空白串）。 */
    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** 截断到指定长度（null 原样返回）。 */
    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}
