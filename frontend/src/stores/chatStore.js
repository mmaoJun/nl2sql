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
  // 当前进行中的流式请求控制器："停止生成"按钮 abort 它（同一时刻只有一个在跑，loading 挡住并发）
  let activeController = null

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
      // 数据库那条 SQL 的执行耗时（SQL_RESULT / FINAL 带出）与端到端耗时（FINAL 带出）
      sqlExecutionTimeMs: null,
      latencyMs: null,
      model: null,
      trace: [],
      streaming: true,
      errorCode: null,
      errorMessage: null,
      stopped: false,
      // 记录本轮请求参数：SSE ERROR 后"重试"要原样重发（同一问题、同一会话）
      question: null,
      datasourceId: null,
      sessionId: null,
      timestamp: Date.now()
    })
    return messages.value[messages.value.length - 1]
  }

  /**
   * SSE 事件 → assistant 消息字段
   * （T9/T11 契约：TEXT_DELTA / TOOL_CALL / TOOL_RESULT / FINAL / ERROR；
   *   SQL_RESULT 为「边跑边出」新增：execute_sql 成功即带完整结果集，早于 FINAL 到达）
   */
  function applyEvent(assistant, { event, data }) {
    switch (event) {
      case 'TEXT_DELTA':
        // 模型边想边说的文本：按产品要求不渲染，正文只出 FINAL 的最终回答，
        // 所以这里直接丢弃（后端 answer 为空时会自己回退到同一批增量，不会丢内容）
        break
      case 'TOOL_CALL':
      case 'TOOL_RESULT':
        // 轨迹按到达顺序原样入列（TOOL_CALL 带 input，TOOL_RESULT 带 summary/success），
        // 载荷都被后端截断到 300 字符，仅供排查；界面不再展示轨迹
        assistant.trace.push(data)
        break
      case 'SQL_RESULT':
        // 结果集提前到达：SQL 与结果表在模型还没写完回答时就先渲染出来
        assistant.sql = data?.sql || assistant.sql
        if (data?.columns) {
          assistant.result = {
            columns: data.columns,
            rows: data.rows || [],
            rowCount: data.rowCount ?? (data.rows?.length || 0)
          }
        }
        if (typeof data?.executionTimeMs === 'number') {
          assistant.sqlExecutionTimeMs = data.executionTimeMs
        }
        break
      case 'FINAL':
        // FINAL 必定送达：以它为准收敛。answer 为空时退回结果行数说明，避免一片空白
        assistant.content = data?.answer || resultSummary(assistant.result)
        assistant.sql = data?.sql || assistant.sql
        // 已有 SQL_RESULT 时结果集不会比 FINAL 更旧，保留即可；FINAL 带 columns 则以其为准
        assistant.result = data?.columns
          ? { columns: data.columns, rows: data.rows || [], rowCount: data.rowCount ?? (data.rows?.length || 0) }
          : assistant.result
        if (typeof data?.sqlExecutionTimeMs === 'number') {
          assistant.sqlExecutionTimeMs = data.sqlExecutionTimeMs
        }
        if (typeof data?.latencyMs === 'number') {
          assistant.latencyMs = data.latencyMs
        }
        assistant.model = data?.model ? { model: data.model } : null
        break
      case 'ERROR':
        // message 单独留字段：错误气泡要原样展示它 + "重试"按钮，
        // 已渲染的增量文本保留在 content 里不覆盖（可能是有价值的部分回答）
        assistant.errorCode = data?.code ?? -1
        assistant.errorMessage = data?.message || '执行失败'
        break
      default:
        break
    }
  }

  /** FINAL 没给回答文本时的兜底：至少说明查到多少行，而不是留一片空白 */
  function resultSummary(result) {
    if (!result) return ''
    return `查询完成，共 ${result.rowCount ?? (result.rows?.length || 0)} 行`
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
        : t.status === 'cancelled'
          ? '已停止生成'
          : (t.errorMessage || '查询失败')
      restored.push({
        role: 'system',
        content: (t.answer && t.answer.trim()) ? t.answer : fallback,
        sql: t.generatedSql || null,
        result: null,
        // 历史轮次只有端到端耗时（chat_turns.execution_time_ms），没有 SQL 耗时
        sqlExecutionTimeMs: null,
        latencyMs: typeof t.executionTimeMs === 'number' ? t.executionTimeMs : null,
        model: t.modelName ? { model: t.modelName } : null,
        trace: [],
        // 取消轮次（T25 后端新增 cancelled 状态）刷新后照样给出"已停止生成"标记
        stopped: t.status === 'cancelled',
        errorCode: null,
        errorMessage: null,
        timestamp: toTimestamp(t.createdAt)
      })
    }
    messages.value = restored
  }

  /**
   * 流式提问核心：追加（可选的）用户消息 + 助手占位消息，驱动 SSE 并把事件落到消息上。
   *
   * @param {boolean} [options.appendUser=true] 是否追加用户消息（重试时用户消息已在列表里）
   */
  async function runStream(question, datasourceId, sessionId, { appendUser = true } = {}) {
    if (appendUser) addUserMessage(question)
    const assistant = addAssistantMessage()
    assistant.question = question
    assistant.datasourceId = datasourceId
    assistant.sessionId = sessionId ?? null
    loading.value = true

    const controller = new AbortController()
    activeController = controller
    try {
      return await streamChat({
        question,
        datasourceId,
        sessionId,
        signal: controller.signal,
        onEvent: (event) => applyEvent(assistant, event)
      })
    } catch (error) {
      if (error && error.name === 'AbortError') {
        // 用户点"停止生成"：不算错误，收尾为"已停止生成"（后端 T25 会把该轮记为 cancelled）
        assistant.stopped = true
      } else {
        // streamChat 内已处理认证跳转；这里只把错误落到消息上，不再二次弹提示
        assistant.errorCode = error?.code ?? -1
        assistant.errorMessage = error?.message || '未知错误'
      }
      return null
    } finally {
      if (activeController === controller) activeController = null
      // streaming 置 false：流式光标随之消失，abort 后不残留半截光标
      assistant.streaming = false
      loading.value = false
    }
  }

  /**
   * 提问并流式接收回答（新问题）。
   *
   * @param {string} question     自然语言问题
   * @param {number} datasourceId 数据源 ID
   * @param {number|null} sessionId 会话 ID（为空则后端自动建会话）
   * @returns {Promise<object|null>} FINAL 载荷（含 sessionId，供调用方保存会话），失败返回 null
   */
  async function sendMessage(question, datasourceId, sessionId) {
    return runStream(question, datasourceId, sessionId)
  }

  /**
   * 重试一条失败的回答：移除失败气泡，用同一问题 / 同一数据源 / 同一会话重发（不重复追问行）。
   * 会话归属沿用失败时的 sessionId；若当时后端还没回填（首问失败），则为空 → 后端按新会话处理。
   */
  async function retryMessage(failed) {
    if (loading.value || !failed || !failed.question) return null
    const idx = messages.value.indexOf(failed)
    if (idx === -1) return null
    messages.value.splice(idx, 1)
    return runStream(failed.question, failed.datasourceId, failed.sessionId, { appendUser: false })
  }

  /** 停止生成：abort 进行中的流（UI 收尾在 runStream 的 finally/catch 里） */
  function stopGeneration() {
    if (activeController) {
      activeController.abort()
      activeController = null
    }
  }

  function clearMessages() {
    messages.value = []
  }

  return { messages, loading, sendMessage, retryMessage, stopGeneration, clearMessages, loadFromTurns, addUserMessage }
})
