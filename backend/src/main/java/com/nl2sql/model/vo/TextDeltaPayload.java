package com.nl2sql.model.vo;

/**
 * SSE {@code TEXT_DELTA} 事件载荷：最终回答正文的一段增量。
 *
 * <p>来源为 AgentScope 的 {@code TextBlockDeltaEvent}。本地通道（Ollama，模型侧
 * {@code stream=false}）下该事件可能缺失——契约允许，{@code FINAL} 必定送达。
 *
 * @param delta 正文增量文本
 */
public record TextDeltaPayload(String delta) {
}
