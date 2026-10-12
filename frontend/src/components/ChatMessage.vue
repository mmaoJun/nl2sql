<template>
  <div :class="['msg-row', message.role === 'user' ? 'msg-user' : 'msg-system']">
    <div class="msg-avatar">
      <div v-if="message.role === 'user'" class="avatar avatar-user">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/>
          <circle cx="12" cy="7" r="4"/>
        </svg>
      </div>
      <div v-else class="avatar avatar-ai">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <rect x="2" y="3" width="20" height="14" rx="2" ry="2"/>
          <path d="M8 21h8M12 17v4"/>
        </svg>
      </div>
    </div>
    <div :class="['message-bubble', message.role === 'user' ? 'message-user' : 'message-system']">
      <div class="message-content">
        {{ message.content }}<span v-if="message.streaming" class="stream-cursor"></span>
      </div>

      <div v-if="message.sql" class="sql-display">
        <div class="sql-header">
          <span class="sql-lang">SQL</span>
        </div>
        <pre class="sql-code">{{ message.sql }}</pre>
      </div>

      <!-- 历史轮次没有 result（库里不存结果行），但模型信息要照样显示：
           做双模型对比时，回看旧对话得知道当时那一轮用的是哪个模型 -->
      <div v-if="message.result || message.model" class="result-meta">
        <template v-if="message.result">
          <span class="meta-item">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18M9 3v18"/></svg>
            {{ message.result.columns?.length || 0 }} 列
          </span>
          <span class="meta-divider"></span>
          <span class="meta-item">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
            {{ message.result.rows?.length || 0 }} 行
          </span>
        </template>
        <template v-if="message.model">
          <span v-if="message.result" class="meta-divider"></span>
          <!-- provider 可缺省（ChatResult 只回模型名）：拼出非空部分，避免出现 "模型: / qwen2.5-coder:7b" -->
          <span class="meta-item">模型: {{ [message.model.provider, message.model.model].filter(Boolean).join(' / ') }}</span>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 消息气泡：用户提问 + 助手回答。
 *
 * 新架构没有追问改写层（T11：不迁 RewriteGate / QuestionRewriter），
 * 原"已理解为 XX"的改写提示已随契约移除；多轮上下文由后端注入历史实现。
 */
defineProps({
  message: {
    type: Object,
    required: true
  }
})
</script>

<style scoped>
.msg-row {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  align-items: flex-start;
}

.msg-user {
  flex-direction: row-reverse;
}

.msg-avatar {
  flex-shrink: 0;
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.avatar-user {
  background: var(--primary);
  color: #fff;
}

.avatar-ai {
  background: var(--primary-bg);
  color: var(--primary);
  border: 1px solid rgba(79, 70, 229, 0.15);
}

.msg-row .message-bubble {
  max-width: 75%;
}

.msg-user .message-bubble {
  border-bottom-right-radius: 4px;
}

.msg-system .message-bubble {
  border-bottom-left-radius: 4px;
}

.message-content {
  margin-bottom: 4px;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 流式渲染中的输入光标（TEXT_DELTA 逐段追加时给一个"还在写"的视觉锚点） */
.stream-cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  vertical-align: -0.15em;
  background: var(--primary);
  animation: cursorBlink 1s steps(1) infinite;
}

@keyframes cursorBlink {
  0%, 49% { opacity: 1; }
  50%, 100% { opacity: 0; }
}

.sql-display {
  margin: 10px 0 4px;
  border-radius: var(--radius);
  overflow: hidden;
}

.sql-header {
  background: #151d2d;
  padding: 6px 14px;
  display: flex;
  align-items: center;
}

.sql-lang {
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
  letter-spacing: 0.5px;
  text-transform: uppercase;
}

.sql-code {
  background: #1e293b;
  color: #e2e8f0;
  padding: 14px 18px;
  font-family: var(--font-mono);
  font-size: 13px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.6;
  margin: 0;
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-top: none;
}

.result-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--border-light);
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-muted);
}

.meta-item svg {
  color: var(--success);
}

.meta-divider {
  width: 1px;
  height: 12px;
  background: var(--border);
}
</style>
