<template>
  <div class="trace-panel">
    <div class="trace-header">
      <span class="trace-title">执行轨迹</span>
      <span class="trace-count">{{ trace.length }} 步</span>
    </div>
    <div class="trace-list">
      <div
        v-for="(item, idx) in trace"
        :key="`${item.callId || 'item'}-${idx}`"
        class="trace-item"
        :class="{ 'is-result': item.type === 'TOOL_RESULT', 'is-failed': item.success === false }"
      >
        <span class="trace-index">{{ idx + 1 }}</span>
        <span class="trace-tool">{{ item.tool || '未知工具' }}</span>
        <span class="trace-kind">{{ item.type === 'TOOL_RESULT' ? '结果' : '调用' }}</span>
        <span class="trace-text" :title="item.type === 'TOOL_RESULT' ? item.summary : item.input">
          {{ item.type === 'TOOL_RESULT' ? (item.summary || '（空结果）') : (item.input || '（无入参）') }}
        </span>
        <span
          v-if="item.type === 'TOOL_RESULT'"
          class="trace-status"
          :class="item.success === false ? 'failed' : 'ok'"
        >
          {{ item.success === false ? '失败' : '成功' }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 轨迹面板（T12 基础版：按序展示工具名 + 入参摘要 + 结果摘要，含成功/失败标记）。
 *
 * 数据来自 SSE 的 TOOL_CALL / TOOL_RESULT 事件（后端已把入参/结果截断到 300 字符），
 * 事件按到达顺序入列，不做合并 —— 精修（如按 callId 配对、折叠展开）留 W4。
 */
defineProps({
  trace: {
    type: Array,
    default: () => []
  }
})
</script>

<style scoped>
.trace-panel {
  margin: 4px 0 20px 44px;
  max-width: 75%;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}

.trace-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  background: #f8fafc;
  border-bottom: 1px solid var(--border);
}

.trace-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.3px;
}

.trace-count {
  font-size: 12px;
  color: var(--text-muted);
  background: var(--surface);
  padding: 2px 8px;
  border-radius: 10px;
  border: 1px solid var(--border);
}

.trace-list {
  display: flex;
  flex-direction: column;
  max-height: 260px;
  overflow-y: auto;
}

.trace-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 16px;
  font-size: 12.5px;
  border-bottom: 1px solid var(--border-light);
}

.trace-item:last-child {
  border-bottom: none;
}

.trace-index {
  width: 18px;
  flex-shrink: 0;
  text-align: center;
  font-size: 11px;
  color: var(--text-muted);
  background: var(--bg);
  border-radius: 4px;
  padding: 1px 0;
}

.trace-tool {
  flex-shrink: 0;
  font-family: var(--font-mono);
  font-weight: 600;
  color: var(--primary);
}

.trace-kind {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-muted);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 5px;
}

.trace-item.is-result .trace-kind {
  color: var(--success);
  border-color: rgba(16, 185, 129, 0.35);
}

.trace-text {
  flex: 1;
  min-width: 0;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.trace-status {
  flex-shrink: 0;
  font-size: 11px;
  padding: 0 6px;
  border-radius: 4px;
}

.trace-status.ok {
  color: var(--success);
  background: var(--success-bg);
}

.trace-status.failed {
  color: var(--error);
  background: var(--error-bg);
}

.trace-item.is-failed .trace-text {
  color: var(--error);
}
</style>
