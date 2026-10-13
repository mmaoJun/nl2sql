<template>
  <div class="chat-page">
    <SessionList
      v-model:collapsed="sidebarCollapsed"
      :sessions="sessionStore.sessions"
      :current-id="sessionStore.currentSessionId"
      :loading="sessionStore.loading"
      @create="handleCreateSession"
      @select="handleSelectSession"
      @rename="handleRenameSession"
      @remove="handleRemoveSession"
    />

    <div v-if="isManageView" class="manage-pane">
      <DataSourcePage />
    </div>

    <div v-else class="chat-main">
    <div v-if="loadError" class="load-error">
      <span>历史消息加载失败</span>
      <button class="retry-btn" @click="reloadAfterFailure">重试</button>
    </div>

    <!-- 新对话欢迎页：问候语 + 居中输入框 + 框下选数据源 + 示例问题（参照截图） -->
    <div v-if="!restoring && chatStore.messages.length === 0" class="welcome">
      <h2 class="welcome-title">Hi {{ username || '朋友' }}，有什么可以帮你的？</h2>
      <div class="welcome-input">
        <ChatInput
          hide-hint
          :placeholder="selectedDsId ? '想查什么，一问即得' : '请先选择数据源'"
          :disabled="chatStore.loading || !selectedDsId"
          :loading="chatStore.loading"
          @send="handleSend"
          @stop="handleStop"
        />
        <div class="welcome-selectors">
          <DataSourceSelector
            v-model="selectedDsId"
            label="数据源"
            :data-sources="dsStore.dataSources"
          />
          <span v-if="!selectedDsId" class="welcome-prompt">选择数据源后才能开始提问</span>
        </div>
      </div>
      <div class="welcome-examples">
        <div class="examples-label">试试这些示例问题</div>
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
    </div>

    <template v-else>
    <div class="chat-body">
      <div class="chat-messages" ref="messagesRef">
        <!-- 消息列与输入框同宽（900px 居中），无头像直排（参照黑白灰改版截图） -->
        <div class="chat-column">
        <!-- 首屏/切会话的轻量骨架屏（el-skeleton，避免长时间空白） -->
        <div v-if="restoring" class="chat-skeleton">
          <el-skeleton :rows="3" animated />
          <el-skeleton :rows="3" animated class="skeleton-gap" />
        </div>

        <template v-else>
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
    </div>

    <div class="chat-input-area">
      <ChatInput
        :disabled="chatStore.loading || !selectedDsId"
        :loading="chatStore.loading"
        @send="handleSend"
        @stop="handleStop"
      >
        <!-- 对话开始后数据源即锁定：输入框左下只读展示，要换只能新建对话。
             开新对话前的选择入口在欢迎页输入框下方 -->
        <template #footer-left>
          <div
            v-if="selectedDs"
            class="ds-static"
            title="本次对话已锁定数据源，如需更换请新建对话"
          >
            <svg
              v-if="brandOf(selectedDs.type)"
              class="ds-static-logo"
              :viewBox="brandOf(selectedDs.type).viewBox"
              fill="currentColor"
              aria-hidden="true"
            >
              <path :d="brandOf(selectedDs.type).path" />
            </svg>
            <el-icon v-else :size="14" class="ds-static-logo"><Coin /></el-icon>
            <span class="ds-static-name">{{ selectedDs.name }}</span>
          </div>
        </template>
      </ChatInput>
    </div>
    </template>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, watch, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useChatStore } from '../stores/chatStore'
import { useDataSourceStore } from '../stores/datasourceStore'
import { useSessionStore } from '../stores/sessionStore'
import ChatInput from '../components/ChatInput.vue'
import ChatMessage from '../components/ChatMessage.vue'
import ResultTable from '../components/ResultTable.vue'
import TracePanel from '../components/TracePanel.vue'
import SessionList from '../components/SessionList.vue'
import DataSourceSelector from '../components/DataSourceSelector.vue'
import DataSourcePage from './DataSourcePage.vue'
import { BRAND_ICONS } from '../assets/brandIcons'
import { Coin } from '@element-plus/icons-vue'

const chatStore = useChatStore()
const dsStore = useDataSourceStore()
const sessionStore = useSessionStore()
const route = useRoute()
const router = useRouter()
// /datasources 复用本外壳：右侧换成数据源管理，左侧会话栏常驻
const isManageView = computed(() => route.path.startsWith('/datasources'))
const messagesRef = ref(null)
const selectedDsId = ref(null)
/** 当前选中的数据源对象（锁定展示用；切换入口只存在于欢迎页） */
const selectedDs = computed(() => dsStore.dataSources.find((d) => d.id === selectedDsId.value) || null)
const loadError = ref(false)

/** 类型对应的品牌标识；未知类型返回 null，由模板退回通用图标 */
function brandOf(code) {
  return BRAND_ICONS[code] || null
}
// 欢迎页问候语用（与侧栏同源，读 localStorage 的登录名）
const username = ref(localStorage.getItem('username') || '')
// 首屏 / 切会话的历史加载中（骨架屏），与 chatStore.loading（生成中）分开
const restoring = ref(false)
// 侧栏收起态持久化：窄栏只看数据源 / 新对话 / 用户头像
const sidebarCollapsed = ref(localStorage.getItem('sidebar-collapsed') === '1')
watch(sidebarCollapsed, (value) => {
  localStorage.setItem('sidebar-collapsed', value ? '1' : '0')
})

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

function handleCreateSession() {
  // 新建对话数据源回到"未选择"（先选源再提问）；会话不预建，首问由后端自动创建
  // 并在 FINAL 里回填 sessionId（T11 契约，见 handleSend 注释），避免产生空会话
  chatStore.clearMessages()
  loadError.value = false
  selectedDsId.value = null
  if (isManageView.value) router.push('/chat')
}

async function handleSelectSession(id) {
  if (isManageView.value) router.push('/chat')
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
  // 数据源不默认选中：由用户在欢迎页显式选择（未选时输入与发送保持禁用）
  await Promise.all([dsStore.fetchDataSources(), dsStore.fetchSupportedTypes()])
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
   min-width:0 是解除 flex 子项"最小宽度等于内容宽度"的默认值，不加的话长 SQL 会把列表挤没。
   主区域白底（侧栏浅灰），与黑白灰改版截图一致 */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100%;
  background: var(--surface);
}

/* 数据源管理页在右侧面板内渲染（左侧会话栏常驻） */
.manage-pane {
  flex: 1;
  min-width: 0;
  height: 100%;
  overflow: hidden;
  background: var(--surface);
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

/* 消息列与输入框同宽居中 */
.chat-column {
  max-width: 900px;
  margin: 0 auto;
}

/* ---- 新对话欢迎页（问候 + 居中输入 + 框下选源 + 示例） ---- */
.welcome {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  /* 输入框落在页面中间偏上的位置（30vh + 标题高度后约 35%~42%） */
  padding: 30vh 32px 40px;
}

.welcome-title {
  font-size: 26px;
  font-weight: 700;
  color: var(--text);
  letter-spacing: -0.01em;
  margin-bottom: 44px;
  text-align: center;
}

.welcome-input {
  width: 100%;
  max-width: 840px;
}

.welcome-input :deep(.chat-input-wrapper) {
  max-width: 100%;
}

.welcome-selectors {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-top: 2px;
  padding-left: 2px;
}

.welcome-prompt {
  font-size: 12.5px;
  color: var(--text-muted);
  margin-left: 2px;
}

/* 输入框左下的数据源：只读，无描边 / 无 hover / 无 pointer（本次对话不再可切换） */
.ds-static {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border: none;
  border-radius: 8px;
  background: var(--surface-hover);
  color: var(--text-secondary);
  font-size: 12.5px;
  cursor: default;
  user-select: none;
}

/* 用 --text 而非 muted：14px 的细线品牌标识在 #9ca3af 上太淡，基本看不出形态 */
.ds-static-logo {
  width: 14px;
  height: 14px;
  color: var(--text);
  flex-shrink: 0;
}

.ds-static-name {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.welcome-examples {
  margin-top: 56px;
  width: 100%;
  max-width: 840px;
}

.examples-label {
  text-align: center;
  font-size: 13px;
  color: var(--text-muted);
  margin-bottom: 16px;
}

/* 骨架屏：仅占对话区，padding 与消息区对齐 */
.chat-skeleton {
  padding: 8px 0;
}

.skeleton-gap {
  margin-top: 24px;
}

.example-queries {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: center;
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
  border-color: var(--primary-light);
  background: var(--primary-bg);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
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
