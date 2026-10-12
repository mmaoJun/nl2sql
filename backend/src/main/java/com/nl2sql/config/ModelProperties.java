package com.nl2sql.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型通道配置（{@code nl2sql.model}）。
 *
 * <p>双轨模型：本地 Ollama（OpenAI 兼容通道，默认装配，无需任何 key）
 * 与云端 DashScope（qwen）。云端 key 从环境变量 {@code DASHSCOPE_API_KEY} 注入，
 * 缺省为空串——缺 key 不阻塞启动，仅当 {@link #provider} 显式指定 cloud 时回退本地
 * （详见 {@link AgentConfig}）。
 *
 * <p>键名与默认值：
 * <ul>
 *   <li>{@code nl2sql.model.dashscope-api-key}：默认空串（来自 {@code DASHSCOPE_API_KEY}）</li>
 *   <li>{@code nl2sql.model.cloud-model}：默认 {@code qwen-plus}</li>
 *   <li>{@code nl2sql.model.ollama-base-url}：默认 {@code http://localhost:11434}（不含 {@code /v1}）</li>
 *   <li>{@code nl2sql.model.local-model}：默认 {@code qwen2.5-coder:7b}</li>
 *   <li>{@code nl2sql.model.local-temperature}：默认 {@code 0.1}（本地模型工具格式稳定性）</li>
 *   <li>{@code nl2sql.model.provider}：默认 {@code local}，可选 {@code local} / {@code cloud}</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "nl2sql.model")
public class ModelProperties {

    /** DashScope API Key；缺省空串表示未配置云端 key。 */
    private String dashscopeApiKey = "";

    /** 云端模型名（DashScope 通道）。 */
    private String cloudModel = "qwen-plus";

    /** Ollama 服务地址（不含 /v1，装配时自动补全 OpenAI 兼容路径）。 */
    private String ollamaBaseUrl = "http://localhost:11434";

    /** 本地模型名（Ollama 通道）。 */
    private String localModel = "qwen2.5-coder:7b";

    /**
     * 本地模型采样温度。
     *
     * <p>qwen2.5-coder:7b 默认温度（0.8）下会把工具调用写成自由文本回答（甚至编数据），
     * 实测 0 / 0.1 下"整段输出工具调用 JSON"的格式遵循稳定（各 6/6、4/4），取 0.1。
     */
    private Double localTemperature = 0.1;

    /** ReActAgent 默认装配的通道：{@code local} 或 {@code cloud}。 */
    private String provider = "local";
}
