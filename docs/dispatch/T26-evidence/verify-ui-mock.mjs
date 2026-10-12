/**
 * T26 UI 行为实测（真实浏览器 + 真实前端代码路径，后端 8080 不可用时以网络层 mock 替代）。
 *
 * 用法：node --experimental-websocket verify-ui-mock.mjs
 *
 * 说明：
 * - REST（登录/会话/数据源/轮次）经 CDP Fetch 拦截返回 mock 响应（真实 axios 代码路径不变）；
 * - SSE 聊天流在页面内以真实捕获帧（T12 q1.sse）+ 合成失败帧回放，可控制帧间隔，
 *   用于验证：配对 / 折叠 / 失败配色 / 停止生成 / 错误气泡 + 重试 / 403 跳转 / 骨架屏。
 * - 每次运行产出的断言写入 t26-mock-results.json，截图写入本目录。
 */
import { spawn } from 'node:child_process'
import { mkdtempSync, writeFileSync, mkdirSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const OUT = 'F:/FANG_ZHENG/nl2sql/docs/dispatch/T26-evidence'
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9336
const BASE = 'http://127.0.0.1:5173'
mkdirSync(OUT, { recursive: true })

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const results = { startedAt: new Date().toISOString(), phases: {} }
const save = () => writeFileSync(join(OUT, 't26-mock-results.json'), JSON.stringify(results, null, 2))

// ── 真实 SSE 帧（T12 实拍）→ 帧数组；补 1 组合成失败帧（结构同真帧，失败态配色取证用）──
const rawSse = readFileSync('F:/FANG_ZHENG/nl2sql/docs/dispatch/T12-evidence/q1.sse', 'utf-8')
const realFrames = rawSse.split(/\r?\n\r?\n/).map((s) => s.trim()).filter(Boolean)
  .map((f) => f.replace(/"sessionId":180/, '"sessionId":901'))
const syntheticFailFrames = [
  'event:TOOL_CALL\ndata:{"type":"TOOL_CALL","tool":"execute_sql","callId":"call_t26fail","input":"{\\"datasource_id\\":5,\\"sql\\":\\"SELECT * FROM not_exists\\"}"}',
  'event:TOOL_RESULT\ndata:{"type":"TOOL_RESULT","tool":"execute_sql","callId":"call_t26fail","summary":"SQL 执行失败：Table \'nlp2sql_test.not_exists\' doesn\'t exist","success":false}'
]
// 失败帧插在 TEXT_DELTA 之前，重排 FINAL 使其含 trace（FINAL 的 trace 字段前端不消费，无碍）
const framesNormal = [...realFrames.slice(0, 6), ...syntheticFailFrames, ...realFrames.slice(6)]
const framesRetry = framesNormal

// ── 后端 mock（CDP Fetch 层，仅 /api/v1/*）──
const json = (data) => JSON.stringify({ code: 200, message: 'success', data })
const MOCK_DELAY = { turns: 0 }
function mockRoute(pathname, method) {
  if (pathname.endsWith('/auth/login') || pathname.endsWith('/auth/register')) {
    return { body: json({ token: 'mock-t26-token', username: 't26mock' }) }
  }
  if (pathname.endsWith('/datasources')) {
    return { body: json([{ id: 5, name: '测试库(25表)', type: 'mysql', host: 'localhost', port: 3306, databaseName: 'nlp2sql_test', username: 'root' }]) }
  }
  if (/\/sessions\/\d+\/turns$/.test(pathname)) {
    return {
      delay: MOCK_DELAY.turns,
      body: json([
        { id: 1, question: '统计 users 表中共有多少个用户', answer: '系统中共有 200 个用户。', generatedSql: 'SELECT COUNT(*) FROM users', status: 'ok', rowCount: 1, executionTimeMs: 5, datasourceId: 5, modelName: 'qwen2.5-coder:7b', createdAt: '2026-10-12T16:00:00' },
        { id: 2, question: '查询所有用户', answer: '', status: 'cancelled', datasourceId: 5, createdAt: '2026-10-12T16:01:00' }
      ])
    }
  }
  if (pathname.endsWith('/sessions') && method === 'POST') {
    return { body: json({ id: 902, title: '新对话' }) }
  }
  if (pathname.endsWith('/sessions')) {
    return { body: json([{ id: 901, title: '统计 users 表中共有多少个用户', turnCount: 2, updatedAt: '2026-10-12T16:01:00', datasourceId: 5 }]) }
  }
  return { body: json(null) }
}

const profile = mkdtempSync(join(tmpdir(), 't26-mock-chrome-'))
const chrome = spawn(CHROME, [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, '--window-size=1440,900'
], { stdio: 'ignore' })

async function findTarget() {
  for (let i = 0; i < 40; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
      const page = list.find((t) => t.type === 'page')
      if (page) return page.webSocketDebuggerUrl
    } catch { /* 未就绪 */ }
    await sleep(250)
  }
  throw new Error('Chrome CDP 未就绪')
}

const ws = new WebSocket(await findTarget())
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject })

let msgId = 0
const pending = new Map()
ws.onmessage = async (event) => {
  const msg = JSON.parse(event.data)
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id)
    pending.delete(msg.id)
    if (msg.error) reject(new Error(JSON.stringify(msg.error)))
    else resolve(msg.result)
    return
  }
  if (msg.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(msg.params.type)) {
    console.log('[console] ' + msg.params.args.map((a) => a.value || a.description || '').join(' '))
  }
  if (msg.method === 'Runtime.exceptionThrown') {
    console.log('[page-error] ' + (msg.params.exceptionDetails?.exception?.description || msg.params.exceptionDetails?.text))
  }
  if (msg.method === 'Fetch.requestPaused') {
    const params = msg.params
    const url = decodeURIComponent(params.request.url)
    const pathname = new URL(params.request.url).pathname
    console.log(`[mock] ${params.request.method} ${url.replace(/^https?:\/\/[^/]+/, '')}`)
    const mock = mockRoute(pathname, params.request.method)
    ;(async () => {
      try {
        if (mock.delay) await sleep(mock.delay)
        await send('Fetch.fulfillRequest', {
          requestId: params.requestId,
          responseCode: 200,
          responseHeaders: [{ name: 'content-type', value: 'application/json; charset=utf-8' }],
          body: Buffer.from(mock.body, 'utf-8').toString('base64')
        })
      } catch (e) {
        console.log('[mock] fulfill 失败: ' + e.message)
      }
    })()
  }
}

function send(method, params = {}) {
  const id = ++msgId
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
  })
}

async function evaluate(expression) {
  const result = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (result.exceptionDetails) throw new Error('页面脚本异常: ' + (result.exceptionDetails.exception?.description || JSON.stringify(result.exceptionDetails)))
  return result.result.value
}

async function waitFor(expression, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await evaluate(expression)) return true
    await sleep(250)
  }
  console.log(`[warn] 等待超时: ${label}`)
  return false
}

async function shot(name) {
  const { data } = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(join(OUT, name), Buffer.from(data, 'base64'))
  console.log('[shot] ' + name)
}

async function navigate(url) {
  await send('Page.navigate', { url })
  await sleep(1000)
}

const setInputJs = (selector, value) => `(() => {
  const el = document.querySelector(${JSON.stringify(selector)});
  if (!el) return 'not-found';
  const proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, ${JSON.stringify(value)});
  el.dispatchEvent(new Event('input', { bubbles: true }));
  return el.value;
})()`

await send('Page.enable')
await send('Runtime.enable')
await send('Fetch.enable', { patterns: [{ urlPattern: '*api/v1*', requestStage: 'Request' }] })

try {
  // ── 1. 真实登录表单 + mock 认证 ──
  await navigate(`${BASE}/login`)
  await waitFor(`!!document.querySelector('input[placeholder="用户名"]')`, 20000, '登录表单渲染')
  await evaluate(`localStorage.clear(); 'ok'`)
  console.log('[1] 填写用户名: ' + await evaluate(setInputJs('input[placeholder="用户名"]', 't26mock')))
  console.log('[1] 填写密码: ' + await evaluate(setInputJs('input[placeholder="密码"]', 'mock123456')))
  console.log('[1] 点击登录: ' + await evaluate(`(() => {
    const btn = [...document.querySelectorAll('.el-tab-pane button')].find(b => b.textContent.trim() === '登录');
    btn?.click(); return !!btn;
  })()`))
  await sleep(1200)
  results.phases.login = await evaluate(`({ url: location.pathname, token: localStorage.getItem('token'), user: localStorage.getItem('username'), toast: document.querySelector('.el-message')?.textContent?.trim() || '' })`)
  console.log('[1] 登录后: ' + JSON.stringify(results.phases.login))
  if (!results.phases.login.token) {
    await shot('T26-debug-login-fail.png')
    throw new Error('mock 登录失败，终止验证')
  }

  // ── 1b. 登录回跳：带 redirect 进入登录页 → 登录后落到原路由 /settings ──
  await evaluate(`localStorage.clear(); 'ok'`)
  await navigate(`${BASE}/login?redirect=/settings`)
  await waitFor(`!!document.querySelector('input[placeholder="用户名"]')`, 20000, '登录表单渲染(带 redirect)')
  await evaluate(setInputJs('input[placeholder="用户名"]', 't26mock'))
  await evaluate(setInputJs('input[placeholder="密码"]', 'mock123456'))
  await evaluate(`(() => {
    const btn = [...document.querySelectorAll('.el-tab-pane button')].find(b => b.textContent.trim() === '登录');
    btn?.click(); return !!btn;
  })()`)
  await sleep(1500)
  results.phases.redirectBack = await evaluate(`({ url: location.pathname + location.search, token: !!localStorage.getItem('token') })`)
  console.log('[1b] 带 redirect 登录后落点: ' + JSON.stringify(results.phases.redirectBack) + '（期望 /settings）')
  await shot('T26-M1b-redirect-back-settings.png')

  // ── 2. 首屏骨架屏（turns 延迟 1200ms） ──
  MOCK_DELAY.turns = 1200
  await evaluate(`localStorage.setItem('currentSessionId', '901'); 'ok'`)
  await navigate(`${BASE}/chat`)
  await waitFor(`!!document.querySelector('.chat-skeleton .el-skeleton')`, 8000, '骨架屏出现')
  results.phases.skeleton = await evaluate(`(() => ({
    '骨架屏可见': !!document.querySelector('.chat-skeleton .el-skeleton'),
    '骨架行数': document.querySelectorAll('.chat-skeleton .el-skeleton__item').length
  }))()`)
  console.log('[2] 首屏骨架屏: ' + JSON.stringify(results.phases.skeleton))
  await shot('T26-M2-skeleton-initial.png')
  MOCK_DELAY.turns = 0
  await waitFor(`!document.querySelector('.chat-skeleton')`, 10000, '骨架屏结束')
  results.phases.historyRestored = await evaluate(`[...document.querySelectorAll('.message-bubble .message-content')].map(e => e.textContent.trim().slice(0, 30))`)
  console.log('[2] 历史回显: ' + JSON.stringify(results.phases.historyRestored))

  // ── 3. 安装页面内 SSE mock（可控制帧间隔 / 场景）──
  await evaluate(`(() => {
    const origFetch = window.fetch
    const mkFrames = (raw) => raw
    window.__t26 = { scenario: 'normal', frameDelayMs: 250, frames: [], calls: [], aborted: 0, completed: 0, closed: 0 }
    window.fetch = async (input, init) => {
      const url = typeof input === 'string' ? input : input.url
      if (!url.includes('/api/v1/chat/stream')) return origFetch(input, init)
      window.__t26.calls.push(url)
      const sc = window.__t26.scenario
      if (sc === 'forbidden') return new Response('', { status: 403 })
      if (sc === 'prestream-error') {
        return new Response(JSON.stringify({ code: 4001, message: '数据源不存在或无权访问' }), { status: 200, headers: { 'content-type': 'application/json' } })
      }
      const enc = new TextEncoder()
      const signal = init && init.signal
      const stream = new ReadableStream({
        async start(controller) {
          let errored = false
          const fail = () => { if (!errored) { errored = true; window.__t26.aborted++; try { controller.error(new DOMException('The user aborted a request.', 'AbortError')) } catch {} } }
          if (signal) { if (signal.aborted) return fail(); signal.addEventListener('abort', fail) }
          try {
            for (const f of window.__t26.frames) {
              await new Promise(r => setTimeout(r, window.__t26.frameDelayMs))
              if (errored) return
              controller.enqueue(enc.encode(f + '\\n\\n'))
            }
            controller.close()
            window.__t26.completed++
          } catch { fail() }
        },
        cancel() { window.__t26.closed++ }
      })
      return new Response(stream, { status: 200, headers: { 'content-type': 'text/event-stream' } })
    }
    return 'stream-mock-installed'
  })()`)
  const setScenario = async (scenario, frames, frameDelayMs) => evaluate(`(() => {
    window.__t26.scenario = ${JSON.stringify(scenario)};
    window.__t26.frames = ${JSON.stringify(frames)};
    window.__t26.frameDelayMs = ${frameDelayMs};
    return window.__t26.frames.length;
  })()`)

  // ── 4. 问答：轨迹配对 / 折叠 / 失败配色 / 展开（帧回放 = 真实捕获帧 + 1 组合成失败帧）──
  console.log('[4] 载入帧数: ' + await setScenario('normal', framesNormal, 250))
  await evaluate(setInputJs('.input-textarea', '统计 users 表中共有多少个用户'))
  await sleep(200)
  await evaluate(`document.querySelector('.send-btn')?.click(); 'ok'`)
  await sleep(900)
  results.phases.midStream = await evaluate(`(() => ({
    '加载中': !!document.querySelector('.loading-indicator'),
    '停止按钮': !!document.querySelector('.stop-btn'),
    '轨迹条目(流式中)': [...document.querySelectorAll('.trace-item')].map(e => (e.querySelector('.trace-tool')?.textContent || '') + '/' + (e.querySelector('.trace-status')?.textContent || '')),
    '流式光标': document.querySelectorAll('.stream-cursor').length
  }))()`)
  console.log('[4] 流式中: ' + JSON.stringify(results.phases.midStream))
  await shot('T26-M4a-streaming-pending.png')
  await waitFor(`!document.querySelector('.loading-indicator')`, 30000, '问答完成')
  await sleep(400)

  results.phases.trace = await evaluate(`(() => {
    const items = [...document.querySelectorAll('.trace-item')];
    const texts = [...document.querySelectorAll('.trace-text')];
    const failedItem = items.find(el => el.classList.contains('is-failed'));
    const failedStatus = document.querySelector('.trace-status.failed');
    return {
      '条目数(配对后)': items.length,
      'steps': items.map(el => ({
        tool: el.querySelector('.trace-tool')?.textContent?.trim(),
        status: el.querySelector('.trace-status')?.textContent?.trim(),
        入参: el.querySelectorAll('.trace-field')[0]?.querySelector('.trace-text')?.textContent?.trim()?.slice(0, 40),
        入参title: !!el.querySelectorAll('.trace-field')[0]?.querySelector('.trace-text')?.getAttribute('title'),
        结果: el.querySelectorAll('.trace-field')[1]?.querySelector('.trace-text')?.textContent?.trim()?.slice(0, 40),
        结果title: !!el.querySelectorAll('.trace-field')[1]?.querySelector('.trace-text')?.getAttribute('title'),
        默认折叠: !el.classList.contains('is-expanded')
      })),
      '摘要均单行省略(nowrap)': texts.every(t => getComputedStyle(t).whiteSpace === 'nowrap' && getComputedStyle(t).textOverflow === 'ellipsis'),
      '失败条边框色': failedItem ? getComputedStyle(failedItem).borderLeftColor : 'n/a',
      '失败状态色': failedStatus ? getComputedStyle(failedStatus).color : 'n/a',
      '答案': [...document.querySelectorAll('.msg-system .message-content')].pop()?.textContent?.trim(),
      '表格': { 列: [...document.querySelectorAll('.result-table-wrapper th .cell')].map(e => e.textContent.trim()), 首行: [...document.querySelectorAll('.result-table-wrapper td .cell')].map(e => e.textContent.trim()).slice(0, 2) }
    };
  })()`)
  console.log('[4] 轨迹: ' + JSON.stringify({ ...results.phases.trace, steps: results.phases.trace.steps.map(s => `${s.tool}/${s.status}`) }))
  await evaluate(`document.querySelector('.chat-messages').scrollTop = document.querySelector('.chat-messages').scrollHeight; 'ok'`)
  await shot('T26-M4b-trace-paired-failed.png')

  await evaluate(`document.querySelector('.trace-item .trace-row')?.click(); 'ok'`)
  await sleep(300)
  results.phases.traceExpanded = await evaluate(`(() => {
    const el = document.querySelector('.trace-item');
    return {
      '展开态': el?.classList.contains('is-expanded'),
      '全文块数': el?.querySelectorAll('.trace-pre').length,
      '入参全文(前80字)': el?.querySelector('.trace-pre')?.textContent?.slice(0, 80)?.replace(/\\s+/g, ' ')
    };
  })()`)
  console.log('[4] 展开: ' + JSON.stringify(results.phases.traceExpanded))
  await shot('T26-M4c-trace-expanded.png')
  await evaluate(`document.querySelector('.trace-item .trace-row')?.click(); 'ok'`)
  await sleep(200)
  results.phases.collapseBack = await evaluate(`!document.querySelector('.trace-item')?.classList.contains('is-expanded')`)
  save()

  // ── 5. 停止生成 ──
  await evaluate(`document.querySelector('.new-session-btn')?.click(); 'ok'`)
  await sleep(400)
  console.log('[5] 载入帧数(慢速): ' + await setScenario('normal', framesNormal, 700))
  await evaluate(setInputJs('.input-textarea', '查询年龄大于25岁的用户'))
  await sleep(200)
  await evaluate(`document.querySelector('.send-btn')?.click(); 'ok'`)
  await waitFor(`document.querySelectorAll('.trace-item').length >= 2 || !!document.querySelector('.stop-btn')`, 15000, '停止场景流式进行')
  await sleep(1600)
  results.phases.stopBefore = await evaluate(`(() => ({
    '加载中': !!document.querySelector('.loading-indicator'),
    '轨迹条目': document.querySelectorAll('.trace-item').length,
    '流式光标': document.querySelectorAll('.stream-cursor').length
  }))()`)
  console.log('[5] 停止前: ' + JSON.stringify(results.phases.stopBefore))
  await shot('T26-M5a-streaming-before-stop.png')
  await evaluate(`document.querySelector('.stop-btn')?.click(); 'ok'`)
  await sleep(800)
  results.phases.stopAfter = await evaluate(`(() => ({
    '加载中(false=正确)': !!document.querySelector('.loading-indicator'),
    '停止按钮(false=正确)': !!document.querySelector('.stop-btn'),
    '发送按钮恢复': !!document.querySelector('.send-btn'),
    '残留流式光标(0=正确)': document.querySelectorAll('.stream-cursor').length,
    '气泡停止标记': document.querySelector('.stopped-note')?.textContent?.trim() || '',
    '输入区提示': document.querySelector('.input-hint')?.textContent?.trim() || '',
    'mock侧abort计数': window.__t26.aborted,
    'mock侧completed': window.__t26.completed,
    '轨迹条目': document.querySelectorAll('.trace-item').length
  }))()`)
  console.log('[5] 停止后: ' + JSON.stringify(results.phases.stopAfter))
  await shot('T26-M5b-after-stop.png')
  // 停止后不再有新帧渲染：等 2.5s 再取一次轨迹条目数比对
  const traceCountAfterStop = await evaluate(`document.querySelectorAll('.trace-item').length`)
  await sleep(2500)
  results.phases.stopFrozen = {
    '停止时条目': traceCountAfterStop,
    '2.5s后条目': await evaluate(`document.querySelectorAll('.trace-item').length`),
    '2.5s后仍在加载': await evaluate(`!!document.querySelector('.loading-indicator')`)
  }
  console.log('[5] 流已冻结: ' + JSON.stringify(results.phases.stopFrozen))
  save()

  // ── 6. 错误气泡 + 重试（起流前 4001 → 重试成功）──
  await evaluate(`document.querySelector('.new-session-btn')?.click(); 'ok'`)
  await sleep(400)
  await setScenario('prestream-error', [], 200)
  await evaluate(setInputJs('.input-textarea', '查询所有用户'))
  await sleep(200)
  console.log('[6] 重试场景前会话: ' + await evaluate(`localStorage.getItem('currentSessionId')`))
  await evaluate(`document.querySelector('.send-btn')?.click(); 'ok'`)
  await waitFor(`!!document.querySelector('.error-bubble')`, 10000, '错误气泡出现')
  results.phases.errorBubble = await evaluate(`(() => ({
    '错误文本': document.querySelector('.error-text')?.textContent?.trim() || '',
    '重试按钮': document.querySelector('.error-retry-btn')?.textContent?.trim() || '',
    '加载中(false=正确)': !!document.querySelector('.loading-indicator'),
    '发送按钮恢复': !!document.querySelector('.send-btn')
  }))()`)
  console.log('[6] 错误气泡: ' + JSON.stringify(results.phases.errorBubble))
  await shot('T26-M6a-error-bubble.png')

  // 点重试：改回成功帧 → 断言请求参数与失败那次一致（同问题、同会话）
  await setScenario('normal', framesRetry.slice(-2), 200)
  await evaluate(`document.querySelector('.error-retry-btn')?.click(); 'ok'`)
  await waitFor(`!document.querySelector('.loading-indicator') && !document.querySelector('.error-bubble')`, 20000, '重试完成')
  await sleep(400)
  results.phases.retry = await evaluate(`(() => ({
    '错误气泡已移除': !document.querySelector('.error-bubble'),
    'mock侧全部请求': window.__t26.calls.map(u => decodeURIComponent(u.replace(/^.*\\/api/, '/api'))),
    '答案': [...document.querySelectorAll('.msg-system .message-content')].pop()?.textContent?.trim(),
    '用户消息': [...document.querySelectorAll('.msg-user .message-content')].map(e => e.textContent.trim())
  }))()`)
  console.log('[6] 重试: ' + JSON.stringify(results.phases.retry))
  await shot('T26-M6b-after-retry-success.png')
  save()

  // ── 7. 403：轻提示 + 跳登录（带 redirect 回跳参数）──
  await setScenario('forbidden', [], 200)
  await evaluate(setInputJs('.input-textarea', '这条不会真正执行'))
  await sleep(200)
  await evaluate(`document.querySelector('.send-btn')?.click(); 'ok'`)
  await sleep(900)
  results.phases.forbidden = await evaluate(`(() => ({
    'URL': location.pathname + location.search,
    '提示toast': document.querySelector('.el-message')?.textContent?.trim() || ''
  }))()`)
  console.log('[7] 403: ' + JSON.stringify(results.phases.forbidden))
  await shot('T26-M7-403-toast-redirect.png')
} catch (error) {
  results.error = String((error && error.message) || error)
  console.log('[error] ' + results.error)
} finally {
  results.finishedAt = new Date().toISOString()
  save()
  console.log('[done] 断言写入 ' + OUT + '/t26-mock-results.json')
  ws.close()
  chrome.kill()
  process.exit(0)
}
