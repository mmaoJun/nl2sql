package com.nl2sql.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;
import io.agentscope.extensions.model.openai.dto.OpenAIToolCall;
import io.agentscope.extensions.model.openai.dto.OpenAIFunction;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 本地模型（Ollama / qwen2.5-coder）适配 formatter，解决两个实测的协议兼容问题：
 *
 * <p><b>问题 1：工具调用以文本返回</b>。qwen2.5-coder:7b 经 Ollama（0.34.x）走
 * OpenAI 兼容接口时，工具调用不以结构化 {@code tool_calls} 返回，而是整体落入
 * {@code content}，形如：
 * {@code {"name": "execute_sql", "arguments": {"datasource_id": 5, "sql": "SELECT ..."}}}
 * （模型不输出 Ollama 模板要求的 {@code <tool_call>} 包裹标签，Ollama 解析不到）。
 * 默认 formatter 会把该 JSON 当普通文本回答，ReAct 循环直接结束，工具永远不会被调用。
 * <b>T25 起固化</b>：既然调用本就走文本协议，{@link #applyTools} 不再把 {@code tools}
 * 数组放进请求（Ollama 的 qwen 模板见 tools 会注入自己的 {@code <tool_call>} 脚手架，
 * 与本协议冲突、导致模型偶尔只回寒暄不落 JSON——复现数据见该方法注释）。
 *
 * <p><b>还原规则</b>：在 {@link OpenAIChatFormatter#parseResponse} 之后做一次受限还原——
 * 仅当满足全部条件时才改写为结构化 {@link ToolUseBlock}：
 * <ol>
 *   <li>响应中没有任何结构化工具调用（有就原样放行）；</li>
 *   <li>整段文本是一个工具调用 JSON，或文本尾部（含正文说明、SQL markdown 之后）带有
 *       一个工具调用 JSON；</li>
 *   <li>{@code name} 必须是本次请求经 {@link #applyTools} 注册过的真实工具名
 *       （防止把恰好形如工具调用的文本回答误还原）；</li>
 *   <li>{@code arguments} 为 JSON 对象（或可再解析为对象的字符串）。</li>
 * </ol>
 *
 * <p><b>问题 2：工具结果回传后模型"失语"</b>。该模型未见训过 Ollama 模板的
 * {@code <tool_response>} 约定：裸 JSON 工具结果回传后，模型会反问用户
 * （"请问您需要查询哪个表…"）或复述结果，不会继续输出下一个工具调用。
 * 实测（见 T5 报告）把工具结果包装成中文叙述——"工具 X 已执行，返回结果（JSON）：…
 * 请继续：输出下一个工具调用的 JSON，或给出最终答案（中文）。"——模型即稳定续链。
 * 因此 {@link #doFormat} 只改写<b>发给模型的副本</b>，AgentScope 记忆中的原始
 * 工具结果不被污染；工具调用<b>失败</b>时改用专属续链指令要求修正重试
 * （{@link #TRAILER_RETRY}，T25：失败也走"能答则答"会让模型编数据）。
 *
 * <p>本适配器只挂本地模型通道；云端 DashScope 原生结构化 tool_calls 不经此路径。
 * 由于文本化还原需要拿到<b>完整</b>响应体，本地通道配 {@code stream(false)}
 * （流式响应中每个 SSE chunk 会被单独 parse，整段判定无法成立）。
 */
@Slf4j
public class TextualToolCallFormatter extends OpenAIChatFormatter {

    /** ```json ... ``` 或 ``` ... ``` 围栏。 */
    private static final Pattern FENCE = Pattern.compile("^```[A-Za-z0-9_-]*\\s*|\\s*```$");

    /** Ollama qwen 模板要求的 &lt;tool_call&gt;...&lt;/tool_call&gt; 包裹（个别采样可能带上）。 */
    private static final Pattern TOOL_CALL_TAG =
            Pattern.compile("^<tool_call>\\s*(.*?)\\s*</tool_call>$", Pattern.DOTALL);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 元数据类工具结果后的续链指令（实测措辞：带"或给出最终答案"的宽松版会让
     * qwen2.5-coder 直接臆造最终答案；"立即继续 + 三条禁止"版 3/3 稳定续链）。
     */
    private static final String TRAILER_CONTINUE =
            "请立即继续：输出下一个工具调用的 JSON（不要输出解释、不要展示 SQL、不要向用户提问）。";

    /** 查询类工具（execute_sql）<b>成功</b>后的指令：能答则答，尚未查到时修正重试，均不得反问用户。 */
    private static final String TRAILER_QUERY_DONE =
            "请立即继续：若查询数据已能回答用户问题，直接用中文给出最终答案（先给结论与关键数据）；"
                    + "否则输出下一个工具调用的 JSON（如修正后的 execute_sql）。不要向用户提问。";

    /**
     * 工具调用<b>失败</b>后的指令（T25）：明确"本轮没有数据"，要求修正后重试、禁止编造。
     *
     * <p>背景（T25 实测）：旧实现失败也走 {@link #TRAILER_QUERY_DONE}（"能答则答"），
     * 本地 7B 拿到跨源 JOIN 的引导式报错后 0/4 修正重试——一半直接复述错误信息收尾，
     * 一半<b>凭猜测编出预算数值</b>（幻觉回答）；换成本条指令后 4/4 修正重试、5/5 无幻觉。
     */
    private static final String TRAILER_RETRY =
            "本轮工具调用失败、没有拿到任何查询数据。请立即修正后重新调用工具（只使用本数据源的表，"
                    + "即上文 list_tables 返回的清单，不要引用其他数据源的表名，改用分别查询后用文字对比），"
                    + "在拿到成功结果前不要给出最终答案，也不要凭猜测编造数据。";

    /** 最近一次请求注册过的工具名，作为还原白名单。 */
    private final Set<String> registeredToolNames = ConcurrentHashMap.newKeySet();

    /**
     * 只登记工具名白名单，<b>不把 tools 数组放进请求</b>（覆盖父类实现，T25）。
     *
     * <p>背景（T25 实测）：Ollama 侧 qwen2.5-coder 的聊天模板检测到请求携带 {@code tools}
     * 时会额外注入它自己的 {@code <tool_call>} 工具脚手架说明，与本通道 sysPrompt 教的
     * "纯文本 JSON 协议"互相冲突。冲突下模型在本应发起工具调用的回合（典型：带会话历史的
     * 第二轮提问）约半数概率退化成只输出"好的，请稍等……"的寒暄文本、不落任何 JSON，
     * ReAct 循环把它当最终答案提前结束——{@code CrossSourceFusionE2ETest} 因此红测。
     * 同一构造请求探针（system + 历史 + 本轮问题，各 8-10 次）：
     * 带 tools 数组 JSON 命中 9/20，去掉 tools 数组 11/11。
     *
     * <p>本通道的工具调用本来就全靠 {@link #parseResponse} 从文本还原，请求里的 tools 数组
     * 只承担"让模型知道有哪些工具"的职责——该职责由 sysPrompt 的"可用工具"清单完整覆盖，
     * 去掉数组不影响模型认知与工具执行；白名单注册保留（还原校验仍需要）。
     */
    @Override
    public void applyTools(OpenAIRequest request, List<ToolSchema> tools) {
        if (tools != null) {
            tools.forEach(schema -> {
                if (schema.getName() != null) {
                    registeredToolNames.add(schema.getName());
                }
            });
        }
    }

    /**
     * 请求侧改写（问题 2 的完整解法）：先按"中文叙述 + 续链指令"包装工具结果，再把
     * 整个出站历史统一为纯文本协议（{@link #textualizeProtocol}）——只有模型看到的
     * 历史与自己被要求的输出格式完全一致时，7B 才能稳定续链。
     */
    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> messages) {
        List<Msg> adjusted = new ArrayList<>(messages.size());
        for (Msg message : messages) {
            adjusted.add(frameToolResults(message));
        }
        return textualizeProtocol(super.doFormat(adjusted));
    }

    /**
     * 出站历史统一为"纯文本协议"：
     * <ul>
     *   <li>assistant 结构化 {@code tool_calls} → 还原为模型自己会输出的 JSON 文本
     *       （与 sysPrompt 教的格式同形）；</li>
     *   <li>{@code tool} 角色回执 → 改为 {@code user} 角色文本（已由
     *       {@link #frameToolResults} 包装为中文叙述 + 续链指令）。</li>
     * </ul>
     * 背景：历史里同时出现"结构化 tool_calls + tool 角色回执"的混合协议时，
     * qwen2.5-coder 经 Ollama 偶发直接输出空回复（finish_reason=stop、content 为空），
     * ReAct 循环把它当最终答案而提前结束（复现与消除实测见 T5 报告）。
     */
    private List<OpenAIMessage> textualizeProtocol(List<OpenAIMessage> messages) {
        List<OpenAIMessage> result = new ArrayList<>(messages.size());
        int callTurns = 0;
        int toolTurns = 0;
        for (OpenAIMessage message : messages) {
            if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
                OpenAIMessage converted = new OpenAIMessage();
                converted.setRole("assistant");
                converted.setContent(renderCallsAsText(message.getToolCalls()));
                result.add(converted);
                callTurns++;
            } else if ("tool".equals(message.getRole())) {
                OpenAIMessage converted = new OpenAIMessage();
                converted.setRole("user");
                converted.setContent(message.getContent());
                result.add(converted);
                toolTurns++;
            } else {
                result.add(message);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[T5-local] 出站历史文本化: 调用轮={} 回执轮={} 角色序列={}", callTurns, toolTurns,
                    result.stream().map(OpenAIMessage::getRole).toList());
        }
        return result;
    }

    /** 结构化工具调用 → 与 sysPrompt 同形的文本 JSON（模型自己输出的格式）。 */
    private static String renderCallsAsText(List<OpenAIToolCall> calls) {
        StringBuilder sb = new StringBuilder();
        for (OpenAIToolCall call : calls) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            OpenAIFunction function = call.getFunction();
            String name = function == null ? "" : String.valueOf(function.getName());
            String arguments = function == null || function.getArguments() == null || function.getArguments().isBlank()
                    ? "{}" : function.getArguments();
            sb.append("{\"name\": \"").append(name).append("\", \"arguments\": ").append(arguments).append('}');
        }
        return sb.toString();
    }

    /** 工具结果消息 → 中文叙述包装；非工具结果消息原样返回。 */
    private Msg frameToolResults(Msg message) {
        if (message.getContentBlocks(ToolResultBlock.class).isEmpty()) {
            return message;
        }
        List<ContentBlock> framed = new ArrayList<>(message.getContent().size());
        for (ContentBlock block : message.getContent()) {
            if (block instanceof ToolResultBlock result) {
                String framedText = "工具 " + result.getName() + " 已执行，返回结果（JSON）：\n"
                        + textOf(result.getOutput())
                        + "\n" + trailerFor(result);
                framed.add(new ToolResultBlock(result.getId(), result.getName(),
                        List.of(TextBlock.builder().text(framedText).build()), result.getMetadata()));
            } else {
                framed.add(block);
            }
        }
        return message.withContent(framed);
    }

    /**
     * 工具结果后的续链指令：失败（工具异常 state=ERROR / 业务返回 success=false）一律
     * 要求修正重试；成功时 execute_sql 之后是收尾（作答或修正重试），其余工具之后是继续探索。
     */
    private static String trailerFor(ToolResultBlock result) {
        if (isFailedResult(result)) {
            return TRAILER_RETRY;
        }
        return "execute_sql".equals(result.getName()) ? TRAILER_QUERY_DONE : TRAILER_CONTINUE;
    }

    /**
     * 工具结果是否为失败：工具字段状态非 SUCCESS（异常 / 拒绝 / 中断），
     * 或输出文本是本项目的业务失败 QueryResult（{@code "success": false}）。
     */
    private static boolean isFailedResult(ToolResultBlock result) {
        if (result.getState() != null && result.getState() != ToolResultState.SUCCESS) {
            return true;
        }
        return textOf(result.getOutput()).replace(" ", "").contains("\"success\":false");
    }

    private static String textOf(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        return blocks.stream()
                .filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining("\n"));
    }

    @Override
    public ChatResponse parseResponse(OpenAIResponse response, Instant instant) {
        ChatResponse parsed = super.parseResponse(response, instant);
        if (parsed == null || parsed.getContent() == null || parsed.getContent().isEmpty()) {
            log.warn("[T5-local] 响应解析为空: rawFinishReason={} rawToolCalls={} rawContent={}",
                    rawFinishReason(response), rawToolCalls(response), truncate(String.valueOf(rawContent(response)), 200));
            return parsed;
        }
        List<String> structuredTools = parsed.getContent().stream()
                .filter(ToolUseBlock.class::isInstance)
                .map(block -> ((ToolUseBlock) block).getName())
                .toList();
        String text = parsed.getContent().stream()
                .filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining());
        log.debug("[T5-local] 响应摘要: finishReason={} structuredTools={} textLen={} text={}",
                parsed.getFinishReason(), structuredTools, text.length(), truncate(text, 200));
        if (!structuredTools.isEmpty()) {
            return parsed; // 结构化工具调用已存在，不做任何事
        }
        if (text.isBlank()) {
            log.warn("[T5-local] 模型响应无有效文本（finishReason={}），本轮无输出", parsed.getFinishReason());
            return parsed;
        }
        Optional<TextualToolCall> restored = parseToolCallFromText(text);
        if (restored.isEmpty()) {
            log.debug("[T5-local] 文本未还原为工具调用（按最终回答处理）: {}", truncate(text, 300));
            return parsed;
        }

        TextualToolCall call = restored.get();
        log.debug("[T5-local] 文本化工具调用已还原: name={} input={}", call.toolName(), call.arguments());
        return new ChatResponse(parsed.getId(), List.of(call.toolUseBlock()),
                parsed.getUsage(), parsed.getMetadata(), parsed.getFinishReason());
    }

    /** 还原结果：工具名 + 入参 + 可直接入消息流的 ToolUseBlock。 */
    private record TextualToolCall(String toolName, Map<String, Object> arguments, ToolUseBlock toolUseBlock) {
    }

    /**
     * 从响应文本中还原工具调用。两种命中形态（实测都出现过）：
     * <ol>
     *   <li>整段文本就是工具调用 JSON（可带围栏/&lt;tool_call&gt; 标签）；</li>
     *   <li>正文（说明 + 所写 SQL 的 markdown）之后跟一个工具调用 JSON——
     *       取文本中<b>最后一个</b>满足"name 已注册 + arguments 为对象"的完整 JSON 对象。</li>
     * </ol>
     */
    private Optional<TextualToolCall> parseToolCallFromText(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = FENCE.matcher(text.trim()).replaceAll("").trim();
        Matcher tag = TOOL_CALL_TAG.matcher(trimmed);
        String candidate = tag.matches() ? tag.group(1).trim() : trimmed;

        // 形态 1：整段即一个 JSON 对象
        Optional<TextualToolCall> whole = parseToolCallJson(candidate);
        if (whole.isPresent()) {
            return whole;
        }

        // 形态 2：取文本中最后一个合法工具调用 JSON（正文里的示例/结果 JSON 不在白名单内）
        Optional<TextualToolCall> last = Optional.empty();
        for (String json : findBalancedJsonObjects(candidate)) {
            Optional<TextualToolCall> parsed = parseToolCallJson(json);
            if (parsed.isPresent()) {
                last = parsed;
            }
        }
        return last;
    }

    /** 单个 JSON 对象 → ToolUseBlock（name 必须已注册、arguments 必须可解析为对象）。 */
    private Optional<TextualToolCall> parseToolCallJson(String json) {
        Map<String, Object> call;
        try {
            call = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Optional.empty();
        }

        Object name = call.get("name");
        if (!(name instanceof String toolName) || !registeredToolNames.contains(toolName)) {
            return Optional.empty();
        }

        Optional<Map<String, Object>> arguments = parseArguments(call.get("arguments"));
        if (arguments.isEmpty()) {
            return Optional.empty();
        }

        // content 必须是"arguments 的 JSON 串"：OpenAI 消息转换器直接把它当
        // tool_calls[].function.arguments 回传给模型（传整段候选 JSON 会被判成参数校验失败）
        String argumentsJson;
        try {
            argumentsJson = MAPPER.writeValueAsString(arguments.get());
        } catch (Exception e) {
            return Optional.empty();
        }

        ToolUseBlock toolUseBlock = ToolUseBlock.builder()
                .id("call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20))
                .name(toolName)
                .input(arguments.get())
                .content(argumentsJson)
                .build();
        return Optional.of(new TextualToolCall(toolName, arguments.get(), toolUseBlock));
    }

    /** 扫描文本，返回所有顶层平衡的 {...} 片段（尊重字符串内的括号与转义）。 */
    private static List<String> findBalancedJsonObjects(String text) {
        List<String> objects = new ArrayList<>();
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(text.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    /** arguments 允许是 JSON 对象，或内含 JSON 对象的字符串。 */
    private Optional<Map<String, Object>> parseArguments(Object arguments) {
        if (arguments == null) {
            return Optional.of(Map.of());
        }
        if (arguments instanceof Map<?, ?> map) {
            return Optional.of(new LinkedHashMap<>(toStringKeyedMap(map)));
        }
        if (arguments instanceof String json) {
            try {
                Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
                });
                return Optional.of(parsed);
            } catch (Exception e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Map<String, Object> toStringKeyedMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    /** 原始响应：finishReason（诊断空回复用）。 */
    private static String rawFinishReason(OpenAIResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return "(no choices)";
        }
        return String.valueOf(response.getChoices().get(0).getFinishReason());
    }

    /** 原始响应：content（诊断空回复用）。 */
    private static Object rawContent(OpenAIResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return "(no choices)";
        }
        OpenAIMessage message = response.getChoices().get(0).getMessage();
        return message == null ? "(no message)" : message.getContent();
    }

    /** 原始响应：结构化 tool_calls 数（诊断空回复用）。 */
    private static String rawToolCalls(OpenAIResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return "(no choices)";
        }
        OpenAIMessage message = response.getChoices().get(0).getMessage();
        if (message == null || message.getToolCalls() == null) {
            return "[]";
        }
        return message.getToolCalls().stream()
                .map(call -> call.getFunction() == null ? "?" : call.getFunction().getName())
                .toList()
                .toString();
    }
}
