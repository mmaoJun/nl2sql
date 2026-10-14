<template>
  <!-- 无头像布局：用户气泡靠右、助手回答占满消息列直排（参照黑白灰改版截图） -->
  <div :class="['msg-row', message.role === 'user' ? 'msg-user' : 'msg-system']">
    <div class="msg-block">
      <div :class="['message-bubble', message.role === 'user' ? 'message-user' : 'message-system']">
        <!-- 助手回答的固定顺序：SQL 语句 → 执行结果 → 模型回答 → 底部信息条。
             不展示模型边想边说的思考文本，也不展示执行轨迹（数据仍在 store 里） -->
        <div v-if="message.sql" class="sql-display">
          <div class="sql-header">
            <span class="sql-lang">SQL</span>
            <button class="copy-chip" :title="sqlCopied ? '已复制' : '复制 SQL'" @click="copyText(message.sql, 'sql')">
              <el-icon :size="12"><Check v-if="sqlCopied" /><DocumentCopy v-else /></el-icon>
              <span>{{ sqlCopied ? '已复制' : '复制' }}</span>
            </button>
          </div>
          <pre class="sql-code">{{ message.sql }}</pre>
        </div>

        <!-- 结果集在 execute_sql 返回时就到达（SQL_RESULT），不必等 FINAL -->
        <ResultTable v-if="message.result" :result="message.result" />

        <div v-if="message.content || message.streaming" class="message-content">
          {{ message.content }}<span v-if="message.streaming" class="stream-cursor"></span>
        </div>

        <!-- SSE ERROR / 请求异常：错误气泡内展示后端 message + "重试"（重发同一问题、沿用同一会话） -->
        <div v-if="message.errorMessage" class="error-bubble">
          <div class="error-text">{{ message.errorMessage }}</div>
          <button class="error-retry-btn" @click="$emit('retry')">重试</button>
        </div>

        <!-- 停止生成后的收尾标记（T25 后端把该轮记为 cancelled，刷新会话后同样显示） -->
        <div v-if="message.stopped" class="stopped-note">已停止生成</div>

        <!-- 历史轮次没有 result（库里不存结果行），但模型与耗时信息要照样显示：
             做双模型对比时，回看旧对话得知道当时那一轮用的是哪个模型、花了多久 -->
        <div v-if="metaItems.length" class="result-meta">
          <template v-for="(item, idx) in metaItems" :key="item.key">
            <span v-if="idx > 0" class="meta-divider"></span>
            <span class="meta-item" :class="item.kind">
              <svg v-if="item.icon === 'columns'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18M9 3v18"/></svg>
              <svg v-else-if="item.icon === 'rows'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
              <svg v-else-if="item.icon === 'clock'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>
              {{ item.text }}
            </span>
          </template>
        </div>
      </div>

      <!-- 用户气泡：悬停时气泡右下浮出复制图标 -->
      <button
        v-if="message.role === 'user' && message.content"
        class="bubble-copy"
        :title="bubbleCopied ? '已复制' : '复制内容'"
        @click="copyText(message.content, 'bubble')"
      >
        <el-icon :size="14"><Check v-if="bubbleCopied" /><DocumentCopy v-else /></el-icon>
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { DocumentCopy, Check } from '@element-plus/icons-vue'
import ResultTable from './ResultTable.vue'

/**
 * 消息气泡：用户提问 + 助手回答。
 *
 * 新架构没有追问改写层（T11：不迁 RewriteGate / QuestionRewriter），
 * 原"已理解为 XX"的改写提示已随契约移除；多轮上下文由后端注入历史实现。
 */
const props = defineProps({
  message: {
    type: Object,
    required: true
  }
})

// 重试：由 ChatPage 调用 chatStore.retryMessage（同一问题 / 同一数据源 / 同一会话重发）
defineEmits(['retry'])

/** 耗时展示：1 秒以内给毫秒，超过给秒（一轮问答十几秒，纯毫秒读起来费劲） */
function formatDuration(ms) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)} s` : `${ms} ms`
}

/**
 * 底部信息条：列/行数取自结果集，耗时取自 SQL_RESULT / FINAL。
 *
 * 历史轮次（刷新后回看）只有端到端耗时，SQL 耗时与结果集都不落库，故各条都按有无渲染。
 */
const metaItems = computed(() => {
  const m = props.message || {}
  const items = []
  if (m.result) {
    items.push({ key: 'columns', kind: 'meta-result', icon: 'columns', text: `${m.result.columns?.length || 0} 列` })
    items.push({ key: 'rows', kind: 'meta-result', icon: 'rows', text: `${m.result.rows?.length || 0} 行` })
  }
  if (typeof m.sqlExecutionTimeMs === 'number') {
    items.push({ key: 'sql-cost', kind: 'meta-time', icon: 'clock', text: `SQL 耗时 ${formatDuration(m.sqlExecutionTimeMs)}` })
  }
  if (typeof m.latencyMs === 'number') {
    items.push({ key: 'total-cost', kind: 'meta-time', icon: 'clock', text: `总耗时 ${formatDuration(m.latencyMs)}` })
  }
  if (m.model) {
    // provider 可缺省（ChatResult 只回模型名）：拼出非空部分，避免出现 "模型: / qwen2.5-coder:7b"
    items.push({ key: 'model', kind: 'meta-model', text: `模型: ${[m.model.provider, m.model.model].filter(Boolean).join(' / ')}` })
  }
  return items
})

// 复制反馈：气泡与 SQL 各自独立计数，避免两处同时闪"已复制"
const bubbleCopied = ref(false)
const sqlCopied = ref(false)

/** 非安全上下文（http 访问非 localhost）下 clipboard API 不可用，退回 execCommand */
function legacyCopy(text) {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  try {
    document.execCommand('copy')
  } finally {
    document.body.removeChild(ta)
  }
}

async function copyText(text, target) {
  if (!text) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      legacyCopy(text)
    }
  } catch {
    legacyCopy(text)
  }
  const flag = target === 'sql' ? sqlCopied : bubbleCopied
  flag.value = true
  setTimeout(() => { flag.value = false }, 1500)
}
</script>

<style scoped>
.msg-row {
  display: flex;
  margin-bottom: 24px;
  align-items: flex-start;
}

.msg-user {
  justify-content: flex-end;
}

/* 气泡 + 其下的复制按钮为一列；用户侧整体靠右、宽度上限 70% */
.msg-block {
  display: flex;
  flex-direction: column;
  min-width: 0;
  max-width: 100%;
}

.msg-user .msg-block {
  align-items: flex-end;
  max-width: 70%;
}

.msg-system .msg-block {
  width: 100%;
}

.msg-row .message-bubble {
  max-width: 100%;
}

.msg-system .message-bubble {
  width: 100%;
}

/* 用户气泡悬停时右下浮出复制图标（键盘可达：focus-visible 同样显示） */
.bubble-copy {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  margin-top: 2px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  opacity: 0;
  transition: all var(--transition);
}

.msg-row:hover .bubble-copy,
.bubble-copy:focus-visible {
  opacity: 1;
}

.bubble-copy:hover {
  background: var(--bg);
  color: var(--text);
}

.message-content {
  margin-bottom: 4px;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 错误气泡：danger 语义色（Element Plus 变量），内含"重试" */
.error-bubble {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin: 6px 0 2px;
  padding: 10px 12px;
  border: 1px solid var(--el-color-danger-light-5);
  background: var(--el-color-danger-light-9);
  border-radius: var(--radius);
}

.error-text {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-color-danger);
  word-break: break-word;
}

.error-retry-btn {
  flex-shrink: 0;
  padding: 3px 12px;
  border: 1px solid var(--el-color-danger-light-5);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--el-color-danger);
  font-size: 12.5px;
  cursor: pointer;
  transition: all var(--transition);
}

.error-retry-btn:hover {
  background: var(--el-color-danger);
  border-color: var(--el-color-danger);
  color: #fff;
}

/* 停止生成标记：warning 语义色，与错误态区分 */
.stopped-note {
  display: inline-block;
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  border: 1px solid var(--el-color-warning-light-7);
  border-radius: 4px;
  padding: 1px 8px;
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

/* 浅色代码块：与白底页面同调，避免整块发黑压过正文 */
.sql-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  background: #eef0f3;
  border: 1px solid var(--border);
  border-bottom: none;
  border-radius: var(--radius) var(--radius) 0 0;
  padding: 5px 8px 5px 14px;
}

.sql-lang {
  font-size: 11px;
  font-weight: 600;
  color: #6b7280;
  letter-spacing: 0.5px;
  text-transform: uppercase;
}

.copy-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 11.5px;
  cursor: pointer;
  transition: all var(--transition);
}

.copy-chip:hover {
  background: rgba(0, 0, 0, 0.06);
  color: var(--text);
}

.sql-code {
  background: #f7f8fa;
  color: #24262a;
  padding: 14px 18px;
  font-family: var(--font-mono);
  font-size: 13px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.6;
  margin: 0;
  border: 1px solid var(--border);
  border-top: none;
  border-radius: 0 0 var(--radius) var(--radius);
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

/* 结果集相关的两个图标走 success 绿（与"有结果"的语义一致），
   耗时与模型是中性信息，用 muted，避免整行都在抢眼 */
.meta-item svg {
  color: var(--success);
}

.meta-item.meta-time svg {
  color: var(--text-muted);
}

.meta-divider {
  width: 1px;
  height: 12px;
  background: var(--border);
}
</style>
