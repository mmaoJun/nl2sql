import { defineStore } from 'pinia'
import { ref } from 'vue'
import { chat } from '../api/endpoints'

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

  /**
   * 追加一条用户消息，并返回它在 store 里的**响应式代理**。
   *
   * 必须从 messages.value 里读回来再返回：push 进去的是原始对象，
   * 直接返回那个原始引用的话，后续对它的赋值（改写结果回填）改的是底层数据，
   * 不会触发依赖通知 —— 表现就是"已理解为"提示条永远不出现。
   */
  function addUserMessage(content) {
    messages.value.push({
      role: 'user',
      content,
      resolvedQuestion: null,
      rewriteStatus: null,
      timestamp: Date.now()
    })
    return messages.value[messages.value.length - 1]
  }

  function addSystemMessage(content, sql = null, result = null, model = null) {
    messages.value.push({
      role: 'system',
      content,
      sql,
      result,
      model,
      timestamp: Date.now()
    })
  }

  /**
   * 把后端返回的轮次列表重建成消息数组。
   *
   * 历史轮次不带结果表格：库里就没存结果行，重放旧结果既要重新执行 SQL，
   * 又会遇到"数据和当时不一样"的困惑。只显示 SQL、当时的行数与耗时。
   */
  function loadFromTurns(turns) {
    const restored = []
    for (const t of turns) {
      restored.push({
        role: 'user',
        content: t.question,
        resolvedQuestion: t.resolvedQuestion ?? null,
        rewriteStatus: t.rewriteStatus ?? null,
        timestamp: toTimestamp(t.createdAt)
      })
      restored.push({
        role: 'system',
        content: t.status === 'ok'
          ? `查询成功，共 ${t.rowCount ?? 0} 条结果（耗时 ${t.executionTimeMs ?? 0}ms）· 历史记录，如需数据请重新提问`
          : (t.errorMessage || '查询失败'),
        sql: t.generatedSql || null,
        result: null,
        model: t.modelName ? { model: t.modelName, provider: t.modelProvider } : null,
        timestamp: toTimestamp(t.createdAt)
      })
    }
    messages.value = restored
  }

  async function sendMessage(message, datasourceId, model, sessionId) {
    const userMessage = addUserMessage(message)
    loading.value = true

    try {
      const res = await chat({ message, datasourceId, model, sessionId })
      const response = res.data

      // 改写结果只能等响应回来才知道，所以回填到已经渲染出去的那条用户消息上
      userMessage.resolvedQuestion = response.resolvedQuestion ?? null
      userMessage.rewriteStatus = response.rewriteStatus ?? null

      if (response.success) {
        addSystemMessage(
          `查询成功，共 ${response.rowCount} 条结果（耗时 ${response.executionTimeMs}ms）`,
          response.generatedSql,
          { columns: response.columns, rows: response.rows },
          { model: response.modelName, provider: response.modelProvider }
        )
      } else {
        addSystemMessage(response.errorMessage || '查询失败')
      }
      return response
    } catch (error) {
      addSystemMessage('请求失败: ' + (error.message || '未知错误'))
      return null
    } finally {
      loading.value = false
    }
  }

  function clearMessages() {
    messages.value = []
  }

  return { messages, loading, sendMessage, clearMessages, loadFromTurns, addUserMessage }
})
