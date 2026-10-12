<template>
  <div class="chat-input-wrapper">
    <div class="input-container">
      <textarea
        ref="textareaRef"
        v-model="inputText"
        class="input-textarea"
        placeholder="输入你的问题，Enter 发送，Shift+Enter 换行"
        :disabled="disabled"
        rows="2"
        @keydown.enter.exact.prevent="handleSend"
      ></textarea>
      <button
        v-if="loading"
        class="stop-btn"
        title="停止生成"
        @click="handleStop"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
          <rect x="6" y="6" width="12" height="12" rx="2"/>
        </svg>
      </button>
      <button
        v-else
        class="send-btn"
        :class="{ active: inputText.trim() && !disabled }"
        :disabled="!inputText.trim() || disabled"
        @click="handleSend"
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <line x1="22" y1="2" x2="11" y2="13"/>
          <polygon points="22 2 15 22 11 13 2 9 22 2"/>
        </svg>
      </button>
    </div>
    <div class="input-hint">
      <span v-if="loading">正在生成回答，点右侧按钮可停止</span>
      <span v-else-if="stoppedNotice" class="hint-stopped">已停止生成</span>
      <span v-else-if="disabled && !inputText.trim()">请先选择数据源</span>
      <span v-else>Enter 发送 · Shift+Enter 换行</span>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  disabled: Boolean,
  // 与 disabled 分开（disabled 还包含"未选数据源"）：加载中要提示的是"生成中"，
  // 不然流式回答期间底部提示会错报成"请先选择数据源"（T12 实测发现）
  loading: Boolean
})

const emit = defineEmits(['send', 'stop'])
const inputText = ref('')
const textareaRef = ref(null)
// 停止后短暂提示"已停止生成"（loading 落下的那一刻闪现，助手气泡里另有持久标记）
const stoppedNotice = ref(false)
let stopRequested = false
let noticeTimer = null

function handleSend() {
  const text = inputText.value.trim()
  if (!text || props.disabled) return
  emit('send', text)
  inputText.value = ''
}

function handleStop() {
  stopRequested = true
  emit('stop')
}

watch(() => props.loading, (value) => {
  if (value || !stopRequested) return
  stopRequested = false
  stoppedNotice.value = true
  clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => { stoppedNotice.value = false }, 4000)
})
</script>

<style scoped>
.chat-input-wrapper {
  max-width: 900px;
  margin: 0 auto;
}

.input-container {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  background: var(--surface);
  border: 2px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 8px 8px 8px 16px;
  transition: all var(--transition);
  box-shadow: var(--shadow);
}

.input-container:focus-within {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(79, 70, 229, 0.1), var(--shadow);
}

.input-textarea {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  font-family: var(--font-sans);
  font-size: 14px;
  line-height: 1.6;
  color: var(--text);
  background: transparent;
  padding: 6px 0;
  min-height: 40px;
  max-height: 120px;
}

.input-textarea::placeholder {
  color: var(--text-muted);
}

.input-textarea:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.send-btn {
  width: 40px;
  height: 40px;
  border-radius: var(--radius);
  border: none;
  background: #e2e8f0;
  color: #94a3b8;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: not-allowed;
  transition: all var(--transition);
  flex-shrink: 0;
}

.send-btn.active {
  background: var(--primary);
  color: #fff;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(79, 70, 229, 0.3);
}

.send-btn.active:hover {
  background: var(--primary-dark);
  box-shadow: 0 4px 12px rgba(79, 70, 229, 0.4);
  transform: translateY(-1px);
}

.send-btn.active:active {
  transform: translateY(0);
}

.stop-btn {
  width: 40px;
  height: 40px;
  border-radius: var(--radius);
  border: 1px solid var(--el-color-danger-light-5);
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all var(--transition);
  flex-shrink: 0;
}

.stop-btn:hover {
  background: var(--el-color-danger-light-8);
  border-color: var(--el-color-danger);
}

.input-hint {
  text-align: center;
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 8px;
}

.hint-stopped {
  color: var(--el-color-warning);
}
</style>
