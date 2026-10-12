<template>
  <div class="chat-page">
    <SessionList
      :sessions="sessionStore.sessions"
      :current-id="sessionStore.currentSessionId"
      :loading="sessionStore.loading"
      @create="handleCreateSession"
      @select="handleSelectSession"
      @rename="handleRenameSession"
      @remove="handleRemoveSession"
    />

    <div class="chat-main">
    <div class="chat-header">
      <div class="header-left">
        <el-icon :size="18" color="var(--primary)"><Connection /></el-icon>
        <DataSourceSelector
          v-model="selectedDsId"
          :data-sources="dsStore.dataSources"
        />
        <ModelSelector v-model="selectedModel" :models="models" />
      </div>
    </div>

    <div v-if="loadError" class="load-error">
      <span>历史消息加载失败</span>
      <button class="retry-btn" @click="reloadAfterFailure">重试</button>
    </div>

    <div class="chat-body">
      <div class="chat-messages" ref="messagesRef">
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
          <ChatMessage :message="msg" />
          <ResultTable v-if="msg.result" :result="msg.result" />
        </template>

        <div v-if="chatStore.loading" class="loading-indicator">
          <div class="loading-dots">
            <span></span><span></span><span></span>
          </div>
          <span class="loading-text">正在分析并生成 SQL...</span>
        </div>
      </div>
    </div>

    <div class="chat-input-area">
      <ChatInput
        :disabled="chatStore.loading || !selectedDsId"
        @send="handleSend"
      />
    </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, watch } from 'vue'
import { useChatStore } from '../stores/chatStore'
import { useDataSourceStore } from '../stores/datasourceStore'
import { useSessionStore } from '../stores/sessionStore'
import { getModels } from '../api/endpoints'
import ChatInput from '../components/ChatInput.vue'
import ChatMessage from '../components/ChatMessage.vue'
import ResultTable from '../components/ResultTable.vue'
import DataSourceSelector from '../components/DataSourceSelector.vue'
import ModelSelector from '../components/ModelSelector.vue'
import SessionList from '../components/SessionList.vue'

const chatStore = useChatStore()
const dsStore = useDataSourceStore()
const sessionStore = useSessionStore()
const messagesRef = ref(null)
const selectedDsId = ref(null)
const loadError = ref(false)
const models = ref([])
const selectedModel = ref(localStorage.getItem('chatModel') || '')

// 记住上次选择：做双模型对比时要反复切换，不记住就得每次重选
watch(selectedModel, (v) => {
  if (v) localStorage.setItem('chatModel', v)
})

async function loadModels() {
  try {
    const res = await getModels()
    models.value = res.data || []
  } catch (e) {
    models.value = []
    return
  }
  const remembered = models.value.find(m => m.id === selectedModel.value && m.available)
  const fallback = models.value.find(m => m.defaultModel && m.available)
      || models.value.find(m => m.available)
  selectedModel.value = remembered ? remembered.id : (fallback ? fallback.id : '')
}

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

  // 后端不做隐式建会话：sessionId 为空是"无会话模式"，评测脚本靠这条语义保持单轮口径。
  // 代价是首轮多一次请求，换来"点了新建但没提问不会产生空会话"。
  let sessionId = sessionStore.currentSessionId
  if (sessionId == null) {
    try {
      const session = await sessionStore.startSession(selectedDsId.value)
      sessionId = session.id
    } catch (e) {
      // 建不出会话不该阻断提问：退回无会话模式，用户至少还能拿到查询结果。
      // 这一轮的对话不会被记住，错误提示已由拦截器弹出。
      sessionId = null
    }
  }

  await chatStore.sendMessage(message, selectedDsId.value, selectedModel.value, sessionId)
  await sessionStore.fetchSessions()
  await nextTick()
  scrollToBottom()
}

function scrollToBottom() {
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  }
}

watch(() => chatStore.messages.length, scrollToBottom)

onMounted(async () => {
  await dsStore.fetchDataSources()
  if (dsStore.dataSources.length > 0) {
    selectedDsId.value = dsStore.dataSources[0].id
  }
  await loadModels()
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
  justify-content: space-between;
  padding: 12px 24px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
  z-index: 1;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
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
