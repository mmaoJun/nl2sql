<template>
  <div class="chat-input-wrapper">
    <div class="input-container" :class="{ 'has-footer': !!$slots['footer-left'] }">
      <!-- 单行起、随内容长高（上限 200px 后内部滚动），发送/停止悬浮在盒内右下 -->
      <textarea
        ref="textareaRef"
        v-model="inputText"
        class="input-textarea"
        placeholder="想查什么，一问即得"
        :disabled="disabled"
        rows="1"
        @input="autoResize"
        @keydown.enter.exact.prevent="handleSend"
      ></textarea>
      <div v-if="$slots['footer-left']" class="input-footer">
        <div class="input-footer-left">
          <slot name="footer-left" />
        </div>
      </div>
      <div class="input-actions">
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
            <line x1="12" y1="19" x2="12" y2="6"/>
            <polyline points="6 12 12 6 18 12"/>
          </svg>
        </button>
      </div>
    </div>
    <div v-if="!hideHint" class="input-hint">
      <span v-if="loading">正在生成回答，点右侧按钮可停止</span>
      <span v-else-if="stoppedNotice" class="hint-stopped">已停止生成</span>
      <span v-else-if="disabled && !inputText.trim()">请先选择数据源</span>
      <span v-else>Enter 发送 · Shift+Enter 换行</span>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, onMounted } from 'vue'

const props = defineProps({
  disabled: Boolean,
  // 与 disabled 分开（disabled 还包含"未选数据源"）：加载中要提示的是"生成中"，
  // 不然流式回答期间底部提示会错报成"请先选择数据源"（T12 实测发现）
  loading: Boolean,
  // 欢迎页不需要底部提示行（选源入口就在下方，快捷键提示也多余）
  hideHint: Boolean
})

const emit = defineEmits(['send', 'stop'])
const inputText = ref('')
const textareaRef = ref(null)
// 停止后短暂提示"已停止生成"（loading 落下的那一刻闪现，助手气泡里另有持久标记）
const stoppedNotice = ref(false)
let stopRequested = false
let noticeTimer = null

const MAX_HEIGHT = 200

// 高度跟随内容：先归零再量 scrollHeight，封顶后由 textarea 自己滚动
function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, MAX_HEIGHT)}px`
}

watch(inputText, () => nextTick(autoResize))
onMounted(autoResize)

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

/* 单盒结构：textarea（+ 可选 chip 行），发送/停止绝对定位在右下 */
.input-container {
  position: relative;
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 12px 54px 12px 16px;
  transition: border-color var(--transition), box-shadow var(--transition);
  box-shadow: var(--shadow-sm);
}

/* 聚焦只加投影、不动边框：让人感知到选中，但不把整框描重 */
.input-container:focus-within {
  border-color: var(--border);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.08);
}

.input-textarea {
  display: block;
  width: 100%;
  border: none;
  outline: none;
  resize: none;
  font-family: var(--font-sans);
  font-size: 14px;
  line-height: 1.6;
  color: var(--text);
  background: transparent;
  padding: 0;
  min-height: 22px;
  max-height: 200px;
  overflow-y: auto;
}

.input-textarea::placeholder {
  color: var(--text-muted);
}

.input-textarea:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* chip 行（对话态）：左侧数据源 chip，右侧给悬浮按钮让位 */
.input-footer {
  display: flex;
  align-items: center;
  margin-top: 8px;
  padding-right: 0;
}

.input-footer-left {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

/* 无 chip 行时按钮相对整框垂直居中；有 chip 行时贴齐该行 */
.input-actions {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  align-items: center;
  gap: 6px;
}

.input-container.has-footer .input-actions {
  top: auto;
  bottom: 10px;
  transform: none;
}

.send-btn {
  width: 34px;
  height: 34px;
  border-radius: 50%;
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
  width: 34px;
  height: 34px;
  border-radius: 50%;
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
