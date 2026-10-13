<template>
  <div class="chat-input-wrapper">
    <div class="input-container">
      <textarea
        ref="textareaRef"
        v-model="inputText"
        class="input-textarea"
        placeholder="想查什么，一问即得"
        :disabled="disabled"
        rows="2"
        @keydown.enter.exact.prevent="handleSend"
      ></textarea>
      <!-- 盒内底行：左侧放数据源 chip 等工具（插槽），右侧停止/发送 -->
      <div class="input-footer">
        <div class="input-footer-left">
          <slot name="footer-left" />
        </div>
        <div class="input-footer-right">
          <button
            v-if="loading"
            class="stop-btn"
            title="停止生成"
            @click="handleStop"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
              <rect x="6" y="6" width="12" height="12" rx="2"/>
            </svg>
          </button>
          <button
            v-else
            class="send-btn"
            :class="{ active: inputText.trim() && !disabled }"
            :disabled="!inputText.trim() || disabled"
            title="发送"
            @click="handleSend"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="22" y1="2" x2="11" y2="13"/>
              <polygon points="22 2 15 22 11 13 2 9 22 2"/>
            </svg>
          </button>
        </div>
      </div>
    </div>
    <div class="input-hint">
      <span v-if="loading">正在生成回答，点右侧按钮可停止</span>
      <span v-else-if="stoppedNotice" class="hint-stopped">已停止生成</span>
      <span v-else-if="disabled && !inputText.trim()">请先在左侧选择数据源</span>
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

/* 单盒结构：textarea + 盒内底行，圆角大框（参照截图） */
.input-container {
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 12px 14px 10px;
  transition: all var(--transition);
  box-shadow: var(--shadow-sm);
}

.input-container:focus-within {
  border-color: var(--primary-light);
  box-shadow: 0 0 0 3px rgba(0, 0, 0, 0.04), var(--shadow-sm);
}

.input-textarea {
  width: 100%;
  border: none;
  outline: none;
  resize: none;
  font-family: var(--font-sans);
  font-size: 14px;
  line-height: 1.6;
  color: var(--text);
  background: transparent;
  padding: 2px 2px 8px;
  min-height: 40px;
  max-height: 160px;
}

.input-textarea::placeholder {
  color: var(--text-muted);
}

.input-textarea:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.input-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.input-footer-left {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.input-footer-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.send-btn {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  border: none;
  background: #e8e8ea;
  color: #9ca3af;
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
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.2);
}

.send-btn.active:hover {
  background: var(--primary-dark);
  transform: translateY(-1px);
}

.send-btn.active:active {
  transform: translateY(0);
}

.stop-btn {
  width: 32px;
  height: 32px;
  border-radius: 10px;
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
