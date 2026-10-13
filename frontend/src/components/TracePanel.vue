<template>
  <div class="trace-panel">
    <div class="trace-header">
      <span class="trace-title">执行轨迹</span>
      <span class="trace-count">{{ steps.length }} 次调用</span>
    </div>
    <div class="trace-list">
      <div
        v-for="(step, idx) in steps"
        :key="step.key"
        class="trace-item"
        :class="{
          'is-failed': step.success === false,
          'is-pending': step.success === null,
          'is-expanded': expanded.has(step.key)
        }"
      >
        <div class="trace-row" @click="toggle(step.key)">
          <span class="trace-index">{{ idx + 1 }}</span>
          <span class="trace-tool">{{ step.tool || '未知工具' }}</span>
          <span v-if="step.success === false" class="trace-status failed">失败</span>
          <span v-else-if="step.success === true" class="trace-status ok">成功</span>
          <span v-else class="trace-status pending">进行中</span>
          <span v-if="step.orphan" class="trace-orphan">未配到调用</span>
          <span class="trace-spacer"></span>
          <el-icon class="trace-toggle" :size="12"><ArrowRight /></el-icon>
        </div>

        <div v-if="!expanded.has(step.key)" class="trace-brief">
          <div class="trace-field">
            <span class="trace-label">入参</span>
            <span class="trace-text" :title="oneLine(step.input) || '（无入参）'">
              {{ oneLine(step.input) || '（无入参）' }}
            </span>
          </div>
          <div class="trace-field">
            <span class="trace-label">结果</span>
            <span
              class="trace-text"
              :class="{ 'text-failed': step.success === false, 'text-pending': step.success === null }"
              :title="step.success === null ? '等待结果…' : (oneLine(step.resultSummary) || '（空结果）')"
            >
              {{ step.success === null ? '等待结果…' : (oneLine(step.resultSummary) || '（空结果）') }}
            </span>
          </div>
        </div>

        <div v-else class="trace-detail">
          <div class="trace-block">
            <div class="trace-block-title">入参</div>
            <pre class="trace-pre">{{ pretty(step.input) || '（无入参）' }}</pre>
          </div>
          <div class="trace-block">
            <div class="trace-block-title">
              结果
              <span v-if="step.success === false" class="trace-block-failed">失败</span>
            </div>
            <pre class="trace-pre" :class="{ 'pre-failed': step.success === false }">{{ step.success === null ? '等待结果…' : (pretty(step.resultSummary) || '（空结果）') }}</pre>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 轨迹面板（T26 精修：按 callId 把 TOOL_CALL / TOOL_RESULT 配对为一条）。
 *
 * 数据来自 SSE 的 TOOL_CALL / TOOL_RESULT 事件（后端已把入参/结果截断到 300 字符）。
 * - 配对：同 callId 的"调用 + 结果"合并为一条（工具名 + 入参摘要 + 结果摘要 + 成功/失败态）；
 *   结果尚未到达时显示"进行中"；配不到调用的孤儿结果单独成条并标记。
 * - 折叠：默认折叠长内容，摘要行单行省略 + title 悬浮全文，点击行首区域展开全文（JSON 美化）。
 * - 配色：失败态用 Element Plus 的 danger 语义色。
 */
import { computed, ref, watch } from 'vue'
import { ArrowRight } from '@element-plus/icons-vue'

const props = defineProps({
  trace: {
    type: Array,
    default: () => []
  }
})

/** 按 callId 配对调用与结果（无 callId 时退化为按顺序各自成条，保持可见） */
const steps = computed(() => {
  const list = []
  const byCallId = new Map()
  for (const item of props.trace || []) {
    const key = item?.callId != null ? String(item.callId) : null
    if (item?.type === 'TOOL_RESULT') {
      const matched = key != null ? byCallId.get(key) : null
      if (matched) {
        matched.success = item.success !== false
        matched.resultSummary = item.summary
        if (item.tool) matched.tool = item.tool
      } else {
        list.push({
          key: key != null ? `result-${key}` : `result-${list.length}`,
          tool: item?.tool,
          input: null,
          success: item?.success !== false,
          resultSummary: item?.summary,
          orphan: true
        })
      }
    } else {
      const step = {
        key: key != null ? `call-${key}` : `call-${list.length}`,
        tool: item?.tool,
        input: item?.input,
        success: null,
        resultSummary: null,
        orphan: false
      }
      if (key != null) byCallId.set(key, step)
      list.push(step)
    }
  }
  return list
})

const expanded = ref(new Set())

function toggle(key) {
  const next = new Set(expanded.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expanded.value = next
}

// 消息复用（含按 idx 作 key 的列表）下 trace 可能被整体替换：清掉过期展开态，避免串行
watch(() => props.trace, () => { expanded.value = new Set() })

/** 摘要单行化：折叠多空白，保证单行省略展示不出现空行 */
function oneLine(value) {
  if (value == null || value === '') return ''
  return String(value).replace(/\s+/g, ' ').trim()
}

/** 展开态美化：JSON 字符串尝试格式化（失败原样返回） */
function pretty(value) {
  if (value == null || value === '') return ''
  if (typeof value !== 'string') {
    try {
      return JSON.stringify(value, null, 2)
    } catch {
      return String(value)
    }
  }
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}
</script>

<style scoped>
.trace-panel {
  margin: -8px 0 20px;
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
  max-height: 320px;
  overflow-y: auto;
}

.trace-item {
  border-bottom: 1px solid var(--border-light);
  border-left: 3px solid transparent;
}

.trace-item:last-child {
  border-bottom: none;
}

.trace-item.is-failed {
  border-left-color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}

.trace-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 16px 2px;
  cursor: pointer;
  user-select: none;
}

.trace-spacer {
  flex: 1;
}

.trace-toggle {
  color: var(--text-muted);
  transition: transform var(--transition);
  flex-shrink: 0;
}

.trace-item.is-expanded .trace-toggle {
  transform: rotate(90deg);
}

.trace-row:hover .trace-toggle {
  color: var(--primary);
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

.trace-status {
  flex-shrink: 0;
  font-size: 11px;
  padding: 0 6px;
  border-radius: 4px;
}

.trace-status.ok {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}

.trace-status.failed {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-8);
}

.trace-status.pending {
  color: var(--el-color-info);
  background: var(--el-color-info-light-9);
}

.trace-orphan {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  padding: 0 6px;
  border-radius: 4px;
}

.trace-brief {
  padding: 0 16px 7px 42px;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.trace-field {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.trace-label {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-muted);
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

.trace-text.text-failed {
  color: var(--el-color-danger);
}

.trace-text.text-pending {
  color: var(--text-muted);
  font-style: italic;
}

.trace-detail {
  padding: 6px 16px 10px 42px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.trace-block-title {
  font-size: 11px;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 3px;
}

.trace-block-failed {
  color: var(--el-color-danger);
}

.trace-pre {
  margin: 0;
  padding: 8px 10px;
  background: var(--bg);
  border: 1px solid var(--border-light);
  border-radius: var(--radius);
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-secondary);
  max-height: 180px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.trace-pre.pre-failed {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  border-color: var(--el-color-danger-light-7);
}
</style>
