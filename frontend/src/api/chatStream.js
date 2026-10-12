import { handleUnauthenticated } from './client'

/**
 * 解析单个 SSE 帧（两条换行分隔的帧已由调用方切出）。
 *
 * 按 SSE 规范取 event / data 两个字段；`data:` 后允许一个空格。
 * data 是后端 Jackson 序列化的 JSON，解析失败时原样返回字符串（防御非预期载荷）。
 */
function parseFrame(frame) {
  let eventName = 'message'
  const dataLines = []
  for (const line of frame.split(/\r?\n/)) {
    if (!line || line.startsWith(':')) continue
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') {
      eventName = value
    } else if (field === 'data') {
      dataLines.push(value)
    }
  }
  if (dataLines.length === 0) return null

  const raw = dataLines.join('\n')
  let data = raw
  try {
    data = JSON.parse(raw)
  } catch {
    // 保持原样字符串
  }
  return { event: eventName, data }
}

/**
 * SSE 聊天流客户端（fetch + ReadableStream，不用 EventSource：后者不支持自定义请求头，
 * 拿不到 Authorization）。
 *
 * 后端契约（T9/T11）：
 * - `GET /api/v1/chat/stream?question&datasourceId&sessionId?`，需 `Authorization: Bearer`；
 * - 事件：`TEXT_DELTA`（增量）/ `TOOL_CALL` / `TOOL_RESULT`（轨迹）/ `FINAL`（同 ChatResult，
 *   含 sessionId，必定送达）/ `ERROR`（{code,message}，发后终止）；
 * - 流未开始时的错误（4001 参数 / 3001 会话归属 / 401 未认证）是 HTTP 200 + JSON body，
 *   故 Accept 必须同时带 text/event-stream 与 application/json，否则协商不到 JSON（T10 实测）；
 * - 未认证（无 token / 伪造 token）为 403 空体（Spring Security 默认入口点）。
 *
 * @param {object} options
 * @param {string} options.question     自然语言问题
 * @param {number} options.datasourceId 数据源 ID
 * @param {number|null} options.sessionId 会话 ID（为空则后端自动建会话并在 FINAL 回填）
 * @param {(event: {event: string, data: any}) => void} options.onEvent 事件回调（按到达顺序）
 * @param {AbortSignal} [options.signal] 中断信号
 * @returns {Promise<object|null>} FINAL 载荷（ChatResult），异常终止时抛出
 */
export async function streamChat({ question, datasourceId, sessionId, onEvent, signal }) {
  const params = new URLSearchParams()
  params.set('question', question)
  params.set('datasourceId', String(datasourceId))
  if (sessionId != null) {
    params.set('sessionId', String(sessionId))
  }

  const headers = { Accept: 'text/event-stream, application/json' }
  const token = localStorage.getItem('token')
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  const response = await fetch(`/api/v1/chat/stream?${params.toString()}`, {
    method: 'GET',
    headers,
    signal
  })

  const contentType = response.headers.get('content-type') || ''
  if (!contentType.includes('text/event-stream')) {
    // 非流式响应：起流前错误体，或过滤链拒绝（403 空体）
    const body = await response.json().catch(() => null)
    if (response.status === 401 || response.status === 403) {
      handleUnauthenticated()
    }
    const error = new Error(body?.message || `请求失败（HTTP ${response.status}）`)
    error.code = body?.code ?? response.status
    throw error
  }
  if (!response.body) {
    throw new Error('响应体不可读（当前浏览器不支持流式读取）')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let finalPayload = null

  const emit = (frame) => {
    const parsed = parseFrame(frame)
    if (!parsed) return
    if (parsed.event === 'FINAL') {
      finalPayload = parsed.data
    }
    if (onEvent) onEvent(parsed)
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let match
      while ((match = buffer.match(/\r?\n\r?\n/))) {
        const frame = buffer.slice(0, match.index)
        buffer = buffer.slice(match.index + match[0].length)
        emit(frame)
      }
    }
    // 兜底：正常结束应在帧尾带空行，残留内容也按最后一帧处理
    buffer += decoder.decode()
    if (buffer.trim()) emit(buffer)
  } finally {
    // 用户中断（AbortError）或读完：释放读锁，避免连接被悬挂持有
    reader.releaseLock()
  }

  return finalPayload
}
