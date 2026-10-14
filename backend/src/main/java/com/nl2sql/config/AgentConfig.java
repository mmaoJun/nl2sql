package com.nl2sql.config;

import com.nl2sql.tool.NL2SQLTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * NL2SQL ReActAgent 装配（W2 检查点）。
 *
 * <p>三件事：
 * <ol>
 *   <li>{@link Toolkit} Bean：注册 T4 的 5 个只读 {@code @Tool}；
 *       注意 Toolkit 是<b>可变</b>对象（工具集可在运行时增删），所以未在其上挂
 *       全局 chunk 回调，避免与后续会话级观测互相覆盖。</li>
 *   <li>双轨模型 Bean：本地 Ollama（OpenAI 兼容通道，默认）与云端 DashScope。
 *       云端 Bean 标注 {@link Lazy}：缺 {@code DASHSCOPE_API_KEY} 时无人触发创建，
 *       HTTP 客户端不初始化，<b>不阻塞应用启动与测试编译</b>。</li>
 *   <li>{@link ReActAgent} Bean（name={@value #AGENT_NAME}、maxIters={@value #MAX_ITERS}），
 *       默认装配本地模型；{@code nl2sql.model.provider=cloud} 时切换云端，
 *       云端 key 缺失则打 WARN 回退本地（宁可降级不启动失败）。</li>
 * </ol>
 *
 * <p>{@link #buildAgent(Model, Toolkit)} 是唯一的 Agent 组装入口：默认 Bean 与
 * 云端冒烟测试（复用同一 sysPrompt / Toolkit / maxIters，仅换模型）都走它，
 * 保证两条通道的装配完全一致。
 *
 * <p>sysPrompt 按通道选择（{@link #SYS_PROMPT_LOCAL} / {@link #SYS_PROMPT_CLOUD}）：
 * 本地模型需要"文本化工具调用"协议提示，云端模型走原生 function calling——同一条
 * 提示词跨通道复用会让云端模型把 JSON 调用当文本回答输出（T5 云端冒烟实测）。
 * 两条提示词共同要点：只读硬约束、探索式工具流程、拒绝写操作、SELECT 必带 LIMIT、中文回答。
 *
 * @see ModelProperties
 */
@Configuration
@EnableConfigurationProperties(ModelProperties.class)
@Slf4j
public class AgentConfig {

    /** Agent 名称（会话与轨迹标识）。 */
    public static final String AGENT_NAME = "NL2SQLAgent";

    /**
     * ReAct 最大迭代轮数。
     *
     * <p>取 10 而不是 5：探索阶段一次只查一张表的 schema，四表问题光探索就要 5 次调用
     * （list_tables + 4 次 get_schema），留 5 会让 execute_sql 一次机会都没有。两次实测
     * 日志正好撞满上限：
     * <ul>
     *   <li>「谁买过手机」：list_tables + users/orders/order_items/products 四次 get_schema
     *       = 5 次，直接 {@code 超过最大迭代轮数: maxIters=5}，execute_sql 一次都没调，
     *       模型只能回一句"未找到直接关联字段"；</li>
     *   <li>「谁买过苹果手机」：同上 4 次探索 + 1 次 execute_sql（因列名写错
     *       {@code Unknown column 'oi.user_id' in 'on clause'} 失败）= 5 次，失败后无重试余地。</li>
     * </ul>
     * 按 10 计算：4~5 次探索 + 首次 execute_sql + 按报错自纠（重新 get_schema 再执行）
     * 一两轮，仍有富余。代价是极端情况下多跑几次模型（本地 7B 每轮 1~2s）。
     */
    public static final int MAX_ITERS = 10;

    /** Ollama 不校验 API Key，填非空占位串即可通过 OpenAI 兼容客户端的参数检查。 */
    static final String OLLAMA_PLACEHOLDER_API_KEY = "ollama";

    /** 本地通道 sysPrompt：文本化工具调用协议（Ollama / qwen2.5-coder）。 */
    static final String SYS_PROMPT_LOCAL = "prompts/agent-local-v1.md";

    /** 云端通道 sysPrompt：原生 function calling（DashScope / qwen 系列）。 */
    static final String SYS_PROMPT_CLOUD = "prompts/agent-cloud-v1.md";

    /**
     * 注册 5 个只读工具的 Toolkit。
     *
     * @param tools T4 迁移的 NL2SQL 工具集
     * @return 已注册 list_tables / get_schema / sample_data / validate_sql / execute_sql 的 Toolkit
     */
    @Bean
    public Toolkit nl2sqlToolkit(NL2SQLTools tools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        log.info("[T5] Toolkit 注册完成: {}", toolkit.getToolNames());
        return toolkit;
    }

    /**
     * 本地模型：Ollama 的 OpenAI 兼容通道（默认装配，无 key 也能跑）。
     *
     * <p><b>两个本地化适配决定</b>（qwen2.5-coder:7b 经 Ollama 实测的行为）：
     * <ul>
     *   <li>{@code stream(false)}：该模型把工具调用以纯文本 JSON 落在 content 里
     *       （不是结构化 {@code tool_calls}），需要拿到完整响应体才能还原，
     *       因此本地通道暂不走 SSE 流式；云端通道保持 {@code stream(true)}。</li>
     *   <li>{@link TextualToolCallFormatter}：把"整段内容恰为工具调用 JSON"的响应
     *       还原为结构化 ToolCall，ReAct 循环才能继续。</li>
     * </ul>
     *
     * @param props 模型配置
     * @return 指向 {@code {ollama-base-url}/v1} 的聊天模型
     */
    @Bean
    public OpenAIChatModel localModel(ModelProperties props) {
        String baseUrl = props.getOllamaBaseUrl() + "/v1";
        String modelName = props.getLocalModel();
        log.info("[T5] 本地模型装配: model={} baseUrl={} stream=false temperature={} formatter=TextualToolCallFormatter",
                modelName, baseUrl, props.getLocalTemperature());
        return OpenAIChatModel.builder()
                .apiKey(OLLAMA_PLACEHOLDER_API_KEY)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(false)
                .formatter(new TextualToolCallFormatter())
                .generateOptions(GenerateOptions.builder()
                        .temperature(props.getLocalTemperature())
                        .build())
                .build();
    }

    /**
     * 云端模型：DashScope 通道。
     *
     * <p>{@link Lazy} 延迟创建：缺 key 时该 Bean 不被实例化，启动不失败；
     * 仅当 {@code provider=cloud} 且 key 非空（经 {@link ObjectProvider} 触发）
     * 或测试显式注入时才会真正创建。
     *
     * @param props 模型配置
     * @return DashScope 聊天模型
     */
    @Bean
    @Lazy
    public DashScopeChatModel cloudModel(ModelProperties props) {
        log.info("[T5] 云端模型装配: model={} apiKey={}", props.getCloudModel(),
                props.getDashscopeApiKey().isBlank() ? "(空)" : "(已配置)");
        return DashScopeChatModel.builder()
                .apiKey(props.getDashscopeApiKey())
                .modelName(props.getCloudModel())
                .stream(true)
                .build();
    }

    /**
     * 默认 ReActAgent：按 {@code nl2sql.model.provider} 选择本地 / 云端通道。
     *
     * <p>provider=cloud 但 {@code DASHSCOPE_API_KEY} 为空时打 WARN 并回退本地模型，
     * 保证任何环境变量组合下应用都能启动。
     *
     * @param toolkit            只读工具集
     * @param props              模型配置
     * @param localModel         本地模型
     * @param cloudModelProvider 云端模型（懒创建）
     * @return 装配好的 ReActAgent
     */
    @Bean
    public ReActAgent nl2sqlAgent(Toolkit toolkit,
                                  ModelProperties props,
                                  OpenAIChatModel localModel,
                                  ObjectProvider<DashScopeChatModel> cloudModelProvider) {
        Model model = resolveModel(props, localModel, cloudModelProvider);
        ReActAgent agent = buildAgent(model, toolkit);
        log.info("[T5] ReActAgent 装配完成: name={} model={} maxIters={}",
                agent.getName(), model.getModelName(), agent.getMaxIters());
        return agent;
    }

    /**
     * Agent 组装入口：默认 Bean 与云端冒烟测试共用，保证除模型外装配一致。
     *
     * <p>sysPrompt 按模型通道自动选择：{@link DashScopeChatModel} → 原生工具调用提示；
     * 其余（Ollama OpenAI 兼容通道）→ 文本化工具调用提示。</p>
     *
     * @param model   本次装配使用的模型
     * @param toolkit 只读工具集
     * @return name / sysPrompt / toolkit / maxIters 均与默认装配一致的 ReActAgent
     */
    public ReActAgent buildAgent(Model model, Toolkit toolkit) {
        String sysPromptLocation = model instanceof DashScopeChatModel ? SYS_PROMPT_CLOUD : SYS_PROMPT_LOCAL;
        return ReActAgent.builder()
                .name(AGENT_NAME)
                .sysPrompt(loadSysPrompt(sysPromptLocation))
                .model(model)
                .toolkit(toolkit)
                .maxIters(MAX_ITERS)
                .build();
    }

    /**
     * 按 provider 解析默认模型；云端不可用时回退本地。
     */
    private Model resolveModel(ModelProperties props, OpenAIChatModel localModel,
                               ObjectProvider<DashScopeChatModel> cloudModelProvider) {
        if (!"cloud".equalsIgnoreCase(props.getProvider())) {
            return localModel;
        }
        if (props.getDashscopeApiKey() == null || props.getDashscopeApiKey().isBlank()) {
            log.warn("[T5] nl2sql.model.provider=cloud 但 DASHSCOPE_API_KEY 未配置，回退本地模型 {}",
                    props.getLocalModel());
            return localModel;
        }
        DashScopeChatModel cloudModel = cloudModelProvider.getIfAvailable();
        if (cloudModel == null) {
            log.warn("[T5] 云端模型 Bean 不可用，回退本地模型 {}", props.getLocalModel());
            return localModel;
        }
        return cloudModel;
    }

    /** 读取 sysPrompt。文件缺失或读取失败属于装配期错误，直接快速失败。 */
    private String loadSysPrompt(String location) {
        Resource resource = new ClassPathResource(location);
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("加载 sysPrompt 失败: " + location, e);
        }
    }
}
