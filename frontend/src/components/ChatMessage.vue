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
      <div class="message-content">{{ message.content }}</div>

      <div v-if="rewriteNote" :class="['rewrite-note', { failed: message.rewriteStatus === 'failed' }]">
        {{ rewriteNote }}
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
          <span class="meta-item">模型: {{ message.model.provider }} / {{ message.model.model }}</span>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  message: {
    type: Object,
    required: true
  }
})

/**
 * 改写提示。
 *
 * 改写错了用户必须能一眼看见并重新表述，而不是对着一个莫名的结果发呆 ——
 * 所以这条提示不是可选的润色，是改写功能的信任基础。
 * resolvedQuestion 为空或与原话相同时不显示（本轮未改写）；
 * failed 时显示降级说明，让用户知道系统没接住上下文。
 */
const rewriteNote = computed(() => {
  const m = props.message
  if (m.role !== 'user') return ''
  if (m.rewriteStatus === 'failed') return '未能理解上下文，已按原话查询'
  if (m.resolvedQuestion && m.resolvedQuestion !== m.content) {
    return `已理解为：${m.resolvedQuestion}`
  }
  return ''
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
}

.rewrite-note {
  margin: 6px 0 2px;
  padding: 5px 10px;
  border-left: 2px solid var(--primary-light);
  background: var(--primary-bg);
  border-radius: 0 6px 6px 0;
  font-size: 12px;
  color: var(--text);
  line-height: 1.5;
}

.rewrite-note.failed {
  border-left-color: var(--warning);
  background: rgba(245, 158, 11, 0.08);
  color: var(--text-muted);
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
