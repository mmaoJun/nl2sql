package com.nl2sql.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 会话记忆配置属性，绑定 {@code nl2sql.memory.*}。
 *
 * <p>只保留"上下文窗口轮数"一项：本项目不做追问改写，旧项目 {@code MemoryProperties} 里的
 * {@code rewrite-enabled} / {@code context-max-age-minutes} / {@code rewrite-model}
 * 没有对应的运行时行为，不迁移（要恢复改写层时再补，避免留下没人读的配置项）。
 *
 * @see com.nl2sql.service.ChatService
 */
@Data
@Component
@ConfigurationProperties(prefix = "nl2sql.memory")
public class MemoryProperties {

    /** 上下文窗口轮数：多轮追问时最多注入同会话同数据源的最近几轮（数值与旧项目一致） */
    private int contextTurns = 3;
}
