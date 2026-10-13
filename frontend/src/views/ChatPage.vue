<template>
  <div class="chat-page">
    <SessionList
      v-model:dsId="selectedDsId"
      :sessions="sessionStore.sessions"
      :current-id="sessionStore.currentSessionId"
      :loading="sessionStore.loading"
      :data-sources="dsStore.dataSources"
      @create="handleCreateSession"
      @select="handleSelectSession"
      @rename="handleRenameSession"
      @remove="handleRemoveSession"
    />

    <div class="chat-main">
    <div class="chat-header">
      <!-- 数据源选择已迁入侧栏顶部；此处只保留表结构入口（SchemaExplorer 走 /api/v1/metadata）。
           模型由后端 nl2sql.model.provider 配置决定，无按请求切换接口，ModelSelector 隐藏 -->
      <el-button size="small" text :icon="Grid" @click="schemaVisible = true">表结构</el-button>
    </div>

    <div v-if="loadError" class="load-error">
      <span>历史消息加载失败</span>
      <button class="retry-btn" @click="reloadAfterFailure">重试</button>
    </div>

    <div class="chat-body">
      <div class="chat-messages" ref="messagesRef">
        <!-- 首屏/切会话的轻量骨架屏（el-skeleton，避免长时间空白） -->
        <div v-if="restoring" class="chat-skeleton">
          <el-skeleton :rows="3" animated />
          <el-skeleton :rows="3" animated class="skeleton-gap" />
        </div>

        <template v-else>
          <div v-if="chatStore.messages.length === 0" class="empty-state">
            <div class="empty-icon">
              <svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
                <path d="M8 9h8M8 13h4"/>
              </svg>
            </div>
            <h3 class="empty-title">开始对话</h3>
            <p class="empty-desc">输入自然语言问题，自动生成 SQL 并查询结果</p>
            <div class="example-queries">
              <button
                v-for="q in exampleQueries"
                :key="q"
                class="example-chip"
                @click="fillQuery(q)"
              >
                {{ q }}
              </button>
            </div>
          </div>

          <template v-for="(msg, idx) in chatStore.messages" :key="idx">
            <ChatMessage :message="msg" @retry="handleRetry(msg)" />
            <TracePanel v-if="msg.trace && msg.trace.length" :trace="msg.trace" />
            <ResultTable v-if="msg.result" :result="msg.result" />
          </template>

          <div v-if="chatStore.loading" class="loading-indicator">
            <div class="loading-dots">
              <span></span><span></span><span></span>
            </div>
            <span class="loading-text">正在分析并生成 SQL...</span>
          </div>
        </template>
      </div>
    </div>

    <div class="chat-input-area">
      <ChatInput
        :disabled="chatStore.loading || !selectedDsId"
        :loading="chatStore.loading"
        @send="handleSend"
        @stop="handleStop"
      />
    </div>
    </div>

    <el-drawer v-model="schemaVisible" title="表结构浏览" size="440px">
      <SchemaExplorer :datasource-id="selectedDsId" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, watch, computed } from 'vue'
import { Grid } from '@element-plus/icons-vue'
import { useChatStore } from '../stores/chatStore'
import { useDataSourceStore } from '../stores/datasourceStore'
import { useSessionStore } from '../stores/sessionStore'
import ChatInput from '../components/ChatInput.vue'
import ChatMessage from '../components/ChatMessage.vue'
import ResultTable from '../components/ResultTable.vue'
import TracePanel from '../components/TracePanel.vue'
import SchemaExplorer from '../components/SchemaExplorer.vue'
import SessionList from '../components/SessionList.vue'

const chatStore = useChatStore()
const dsStore = useDataSourceStore()
const sessionStore = useSessionStore()
const messagesRef = ref(null)
const selectedDsId = ref(null)
const loadError = ref(false)
const schemaVisible = ref(false)
// 首屏 / 切会话的历史加载中（骨架屏），与 chatStore.loading（生成中）分开
const restoring = ref(false)

const exampleQueries = [
  '查询所有用户',
  '统计每个部门的用户数量',
  '查询年龄大于25岁的用户',
  '查询订单金额最高的前10个用户',
  '查询名字包含张的用户'
]

function fillQuery(q) {
  handleSend(q)
}

async function handleCreateSession() {
  // 新建走 POST /api/v1/sessions（会话侧栏五端点之一），拿到会话后再提问即带 sessionId
  await sessionStore.startSession(selectedDsId.value)
  chatStore.clearMessages()
  loadError.value = false
}

async function handleSelectSession(id) {
  if (id === sessionStore.currentSessionId) return
  sessionStore.setCurrent(id)
  await restoreSession(id)
}

// 切会话时数据源回填"最后一轮"用的库，而不是 chat_sessions.datasource_id：
// 用户中途换过库时，"接着聊"应该接着最后那次查询的库
async function restoreSession(id) {
  if (id == null) {
    chatStore.clearMessages()
    loadError.value = false
    return
  }
  loadError.value = false
  restoring.value = true
  try {
    const turns = await sessionStore.loadTurns(id)
    chatStore.loadFromTurns(turns)
    const lastTurn = turns[turns.length - 1]
    if (lastTurn && dsStore.dataSources.some((d) => d.id === lastTurn.datasourceId)) {
      selectedDsId.value = lastTurn.datasourceId
    }
    await nextTick()
    scrollToBottom()
  } catch (e) {
    chatStore.clearMessages()
    if (e && e.code === 3001) {
      // 会话已不存在（可能在另一台机器上被删了）：清掉本地记的 id 并重拉列表，
      // 否则会一直卡在一个打不开的会话上
      sessionStore.setCurrent(null)
      await sessionStore.fetchSessions()
    } else {
      // 网络或后端问题：列表保留，对话区给一个重试入口，不把用户踢出当前会话
      loadError.value = true
    }
  } finally {
    restoring.value = false
  }
}

// 重试要同时重拉列表和重载轮次：页面加载时若后端不可用，列表那一步也失败了，
// 只重载轮次的话用户点完重试仍然看到一个空列表，会以为对话都丢了
async function reloadAfterFailure() {
  await sessionStore.fetchSessions()
  await restoreSession(sessionStore.currentSessionId)
}

async function handleRenameSession(id, title) {
  await sessionStore.rename(id, title)
}

async function handleRemoveSession(id) {
  await sessionStore.remove(id)
  if (sessionStore.currentSessionId === null) {
    chatStore.clearMessages()
  }
}

async function handleSend(message) {
  if (!selectedDsId.value) return

  // 提问携带当前会话 id；为空时由后端自动建会话并在 FINAL 里回填（T11 契约：
  // "第一问不带 → 用响应 ChatResult.sessionId 保存 → 后续追问带上"），
  // 因此这里不再预建会话，避免"点了新建但没提问"之外再产生空会话
  const result = await chatStore.sendMessage(message, selectedDsId.value, sessionStore.currentSessionId)
  if (result && result.sessionId && result.sessionId !== sessionStore.currentSessionId) {
    sessionStore.setCurrent(result.sessionId)
  }
  await sessionStore.fetchSessions()
  await nextTick()
  scrollToBottom()
}

/** 停止生成：abort 进行中的 SSE 流；UI 收尾（loading 复位、光标消失、消息标记）由 store 完成。
    停止后顺带刷新会话列表：后端 T25 已把该轮记为 cancelled，新会话（首问被中断时）也能立刻出现在侧栏 */
async function handleStop() {
  chatStore.stopGeneration()
  await sessionStore.fetchSessions()
}

/** 错误气泡"重试"：同一问题 / 同一数据源 / 同一会话重发，成功后照常刷新会话侧栏 */
async function handleRetry(msg) {
  const result = await chatStore.retryMessage(msg)
  if (result && result.sessionId && result.sessionId !== sessionStore.currentSessionId) {
    sessionStore.setCurrent(result.sessionId)
  }
  await sessionStore.fetchSessions()
  await nextTick()
  scrollToBottom()
}

function scrollToBottom() {
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  }
}

// 跟随流式渲染滚动：消息条数不变但正文/轨迹在长的时候也要跟到底部
const messagesDigest = computed(() => chatStore.messages
  .reduce((sum, m) => sum + (m.content?.length || 0) + (m.trace?.length || 0), 0))

watch(messagesDigest, scrollToBottom)

onMounted(async () => {
  await dsStore.fetchDataSources()
  if (dsStore.dataSources.length > 0) {
    selectedDsId.value = dsStore.dataSources[0].id
  }
  await sessionStore.fetchSessions()
  await restoreSession(sessionStore.currentSessionId)
})
</script>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: row;
  height: 100%;
  background: var(--bg);
  overflow: hidden;
}

/* 纵向布局搬到了这一层：.chat-page 改成横向之后，header/body/input 得有个容器继续竖排。
   min-width:0 是解除 flex 子项"最小宽度等于内容宽度"的默认值，不加的话长 SQL 会把列表挤没 */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100%;
}

.chat-header {
  display: flex;
  align-items: center;
  padding: 8px 24px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
  z-index: 1;
}

.load-error {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 10px;
  font-size: 13px;
  color: var(--text-muted);
  background: var(--surface);
  border-bottom: 1px solid var(--border-light);
}

.retry-btn {
  padding: 3px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: transparent;
  color: var(--primary);
  font-size: 12.5px;
  cursor: pointer;
  transition: all var(--transition);
}

.retry-btn:hover {
  background: var(--primary-bg);
  border-color: var(--primary-light);
}

.chat-body {
  flex: 1;
  overflow: hidden;
}

.chat-messages {
  height: 100%;
  overflow-y: auto;
  padding: 24px 32px;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  text-align: center;
}

/* 骨架屏：仅占对话区，padding 与消息区对齐 */
.chat-skeleton {
  padding: 8px 0;
}

.skeleton-gap {
  margin-top: 24px;
}

.empty-icon {
  width: 80px;
  height: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--primary-bg);
  color: var(--primary);
  margin-bottom: 20px;
}

.empty-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text);
  margin-bottom: 6px;
}

.empty-desc {
  font-size: 14px;
  color: var(--text-muted);
  margin-bottom: 28px;
}

.example-queries {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  max-width: 560px;
}

.example-chip {
  padding: 8px 16px;
  border: 1px solid var(--border);
  border-radius: 20px;
  background: var(--surface);
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all var(--transition);
  box-shadow: var(--shadow-sm);
}

.example-chip:hover {
  color: var(--primary);
  border-color: var(--primary);
  background: var(--primary-bg);
  box-shadow: 0 2px 8px rgba(79, 70, 229, 0.12);
}

.loading-indicator {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
}

.loading-dots {
  display: flex;
  gap: 4px;
}

.loading-dots span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--primary);
  animation: dotPulse 1.4s ease-in-out infinite;
}

.loading-dots span:nth-child(2) { animation-delay: 0.2s; }
.loading-dots span:nth-child(3) { animation-delay: 0.4s; }

@keyframes dotPulse {
  0%, 80%, 100% { opacity: 0.3; transform: scale(0.8); }
  40% { opacity: 1; transform: scale(1); }
}

.loading-text {
  font-size: 13px;
  color: var(--text-muted);
}
</style>
