import { defineStore } from 'pinia'
import { ref } from 'vue'
import { streamChat } from '../api/chatStream'

// 后端的时间字段是 ISO 形态（"2026-10-08T17:44:12" 或带小数秒），
// 要去掉 T 与小数秒、再把横杠换成斜杠，否则部分浏览器解析成 Invalid Date
function toTimestamp(value) {
  if (!value) return Date.now()
  const normalized = String(value).split('.')[0].replace('T', ' ').replace(/-/g, '/')
  const time = new Date(normalized).getTime()
  return Number.isNaN(time) ? Date.now() : time
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref([])
  const loading = ref(false)

  /** 追加一条用户消息（提问内容） */
  function addUserMessage(content) {
    messages.value.push({
      role: 'user',
      content,
      timestamp: Date.now()
    })
  }

  /**
   * 追加一条"助手正在回答"的占位消息，并返回它在 store 里的**响应式代理**。
   *
   * 必须从 messages.value 里读回来再返回：push 进去的是原始对象，
   * 直接返回那个原始引用的话，后续对它的赋值（流式增量、轨迹追加、结果回填）
   * 改的是底层数据，不会触发依赖通知 —— 表现就是回答永远停在空白。
   */
  function addAssistantMessage() {
    messages.value.push({
      role: 'system',
      content: '',
      sql: null,
      result: null,
      model: null,
      trace: [],
      streaming: true,
      errorCode: null,
      timestamp: Date.now()
    })
    return messages.value[messages.value.length - 1]
  }

  /** SSE 事件 → assistant 消息字段（T9/T11 契约：TEXT_DELTA / TOOL_CALL / TOOL_RESULT / FINAL / ERROR） */
  function applyEvent(assistant, { event, data }) {
    switch (event) {
      case 'TEXT_DELTA':
        assistant.content += data?.delta || ''
        break
      case 'TOOL_CALL':
      case 'TOOL_RESULT':
        // 轨迹按到达顺序原样入列（TOOL_CALL 带 input，TOOL_RESULT 带 summary/success）
        assistant.trace.push(data)
        break
      case 'FINAL':
        // FINAL 必定送达：以它为准收敛（answer 为空时保留已渲染的增量文本）
        assistant.content = data?.answer || assistant.content
        assistant.sql = data?.sql || null
        assistant.result = data?.columns
          ? { columns: data.columns, rows: data.rows || [], rowCount: data.rowCount ?? (data.rows?.length || 0) }
          : null
        assistant.model = data?.model ? { model: data.model } : null
        break
      case 'ERROR':
        assistant.errorCode = data?.code || null
        assistant.content = assistant.content || (data?.message || '执行失败')
        break
      default:
        break
    }
  }

  /**
   * 把后端返回的轮次列表重建成消息数组。
   *
   * 历史轮次不带结果表格：库里就没存结果行，重放旧结果既要重新执行 SQL，
   * 又会遇到"数据和当时不一样"的困惑。正文用轮次里落库的 answer（T11 契约增量），
   * 辅以当时的 SQL、行数与耗时。
   */
  function loadFromTurns(turns) {
    const restored = []
    for (const t of turns) {
      restored.push({
        role: 'user',
        content: t.question,
        timestamp: toTimestamp(t.createdAt)
      })
      const fallback = t.status === 'ok'
        ? `查询完成，共 ${t.rowCount ?? 0} 行（耗时 ${t.executionTimeMs ?? 0}ms）· 历史记录，如需数据请重新提问`
        : (t.errorMessage || '查询失败')
      restored.push({
        role: 'system',
        content: (t.answer && t.answer.trim()) ? t.answer : fallback,
        sql: t.generatedSql || null,
        result: null,
        model: t.modelName ? { model: t.modelName } : null,
        trace: [],
        timestamp: toTimestamp(t.createdAt)
      })
    }
    messages.value = restored
  }

  /**
   * 提问并流式接收回答。
   *
   * @param {string} question     自然语言问题
   * @param {number} datasourceId 数据源 ID
   * @param {number|null} sessionId 会话 ID（为空则后端自动建会话）
   * @returns {Promise<object|null>} FINAL 载荷（含 sessionId，供调用方保存会话），失败返回 null
   */
  async function sendMessage(question, datasourceId, sessionId) {
    addUserMessage(question)
    const assistant = addAssistantMessage()
    loading.value = true

    try {
      return await streamChat({
        question,
        datasourceId,
        sessionId,
        onEvent: (event) => applyEvent(assistant, event)
      })
    } catch (error) {
      // streamChat 内已处理认证跳转；这里只把错误落到消息上，不再二次弹提示
      assistant.errorCode = error.code || null
      assistant.content = assistant.content || `请求失败：${error.message || '未知错误'}`
      return null
    } finally {
      assistant.streaming = false
      loading.value = false
    }
  }

  function clearMessages() {
    messages.value = []
  }

  return { messages, loading, sendMessage, clearMessages, loadFromTurns, addUserMessage }
})
