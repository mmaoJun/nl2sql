/**
 * T26 前端体验打磨实测驱动脚本（零依赖：Node 20 --experimental-websocket + Chrome CDP）。
 *
 * 用法：
 *   node --experimental-websocket drive-t26.mjs
 *
 * 覆盖场景（真实后端 8080 + 前端 dev 5173 + 真实 LLM 本地 Ollama，共 3 次问答）：
 *   A. 认证：未登录访问 /settings → 守卫跳 /login?redirect=/settings；注册 + 真实登录 → 回跳 /settings
 *   B. 数据源选择 → 真实问答①（轨迹面板：配对 / 折叠 / 摘要省略 title / 展开全文）
 *   C. 骨架屏：网络限速下切换会话，捕获 el-skeleton
 *   D. 停止生成：真实问答②中途点"停止"→ UI 收尾断言（loading 复位 / 无残留光标 / "已停止生成"标记）
 *      + 会话轮次 status=cancelled 核对（后端 T25）+ 刷新后历史回显
 *   E. 错误态 + 重试：一次性把 SSE 请求的 datasourceId 改写为不存在值（真实 4001 响应）→ 错误气泡
 *      → 点"重试" → 真实问答③（同一问题、同一会话）
 *   F. 403：伪造 token 发问 → 轻提示 toast + 跳 /login?redirect=/chat
 */
import { spawn } from 'node:child_process'
import { mkdtempSync, writeFileSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const OUT = 'F:/FANG_ZHENG/nl2sql/docs/dispatch/T26-evidence'
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9334
// 前端 dev server：原 5173 实例只监听 IPv6 [::1] 且随后退出；本任务用 127.0.0.1:5173 新实例（IPv4，Chrome 可达）
const BASE = process.env.T26_BASE || 'http://127.0.0.1:5173'
// 用户名可传参（预检脚本注册过则复用；不传则新注册）
const USER = process.argv[2] || `t26ui${Date.now().toString().slice(-6)}`
const PASS = 'T26pass123'
const STOP_AFTER = process.argv[3] || '' // 预检用：'--precheck' 只跑到数据源选择（0 次 LLM）
mkdirSync(OUT, { recursive: true })

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const results = { user: USER, startedAt: new Date().toISOString(), phases: {} }
const RESULTS_FILE = STOP_AFTER === '--precheck' ? 't26-precheck-results.json' : 't26-results.json'
const save = () => writeFileSync(join(OUT, RESULTS_FILE), JSON.stringify(results, null, 2))

const profile = mkdtempSync(join(tmpdir(), 't26-chrome-'))
const chrome = spawn(CHROME, [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, '--window-size=1440,900'
], { stdio: 'ignore' })

async function findTarget() {
  for (let i = 0; i < 40; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/list`)
      const list = await res.json()
      const page = list.find((t) => t.type === 'page')
      if (page) return page.webSocketDebuggerUrl
    } catch { /* chrome 未就绪 */ }
    await sleep(250)
  }
  throw new Error('Chrome CDP 未就绪')
}

const ws = new WebSocket(await findTarget())
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject })

let msgId = 0
const pending = new Map()
const sseRequests = []
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data)
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id)
    pending.delete(msg.id)
    if (msg.error) reject(new Error(JSON.stringify(msg.error)))
    else resolve(msg.result)
    return
  }
  if (msg.method === 'Network.requestWillBeSent') {
    const url = msg.params.request.url
    if (url.includes('/api/v1/chat/stream')) {
      sseRequests.push(decodeURIComponent(url))
      console.log('[net] SSE 请求: ' + decodeURIComponent(url))
    }
  }
  if (msg.method === 'Network.responseReceived' && msg.params.response.url.includes('/api/v1/')) {
    console.log(`[net] ${msg.params.response.status} ${decodeURIComponent(msg.params.response.url).replace(/^https?:\/\/[^/]+/, '')}`)
  }
  if (msg.method === 'Network.loadingFailed') {
    console.log(`[net] loadingFailed: ${msg.params.errorText} (${msg.params.type})`)
  }
  if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
    console.log('[console.error] ' + msg.params.args.map((a) => a.value || a.description || '').join(' '))
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
  if (result.exceptionDetails) {
    throw new Error('页面脚本异常: ' + (result.exceptionDetails.exception?.description || JSON.stringify(result.exceptionDetails)))
  }
  return result.result.value
}

async function waitFor(expression, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await evaluate(expression)) return true
    await sleep(300)
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
  await sleep(1300)
}

/** Element Plus 输入框：原生 setter + input 事件（直接赋值 v-model 不认） */
const setInputJs = (selector, value) => `(() => {
  const el = document.querySelector(${JSON.stringify(selector)});
  if (!el) return 'not-found';
  const proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, ${JSON.stringify(value)});
  el.dispatchEvent(new Event('input', { bubbles: true }));
  return el.value;
})()`

/** 当前可见 tab 面板内的元素（登录/注册页两个 pane 都在 DOM 里） */
const visiblePaneJs = `[...document.querySelectorAll('.el-tab-pane')].find(p => p.offsetParent !== null)`

await send('Page.enable')
await send('Runtime.enable')
await send('Network.enable')

try {
  // ── A. 认证：守卫跳转带 redirect，登录后回跳 ──
  await navigate(`${BASE}/login`)
  await evaluate(`localStorage.clear(); 'ok'`)
  await navigate(`${BASE}/settings`)
  const guardUrl = await evaluate(`location.pathname + location.search`)
  results.phases.guardRedirect = guardUrl
  console.log('[A] 未登录访问 /settings → ' + guardUrl + '（期望 /login?redirect=/settings）')
  await shot('T26-A1-guard-redirect-login.png')

  // 注册（登录页"注册"页签，走真实后端）；用户名已存在则忽略，继续登录
  await evaluate(`(() => {
    const tab = [...document.querySelectorAll('.el-tabs__item')].find(t => t.textContent.trim() === '注册');
    tab?.click(); return !!tab;
  })()`)
  await sleep(400)
  await evaluate(`(() => {
    const pane = ${visiblePaneJs};
    const inputs = pane.querySelectorAll('input');
    const set = (el, v) => { Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })); };
    set(inputs[0], ${JSON.stringify(USER)});
    set(inputs[1], ${JSON.stringify(PASS)});
    set(inputs[2], ${JSON.stringify(PASS)});
    return [...inputs].map(i => i.value).join(' | ');
  })()`)
  await evaluate(`(() => {
    const pane = ${visiblePaneJs};
    const btn = [...pane.querySelectorAll('button')].find(b => b.textContent.trim() === '注册');
    btn?.click(); return !!btn;
  })()`)
  await sleep(1500)
  results.phases.registerToast = await evaluate(`document.querySelector('.el-message')?.textContent?.trim() || ''`)
  console.log('[A] 注册结果 toast: ' + results.phases.registerToast)

  // 无论注册成败，切到登录页签完成真实登录
  await evaluate(`(() => {
    const tab = [...document.querySelectorAll('.el-tabs__item')].find(t => t.textContent.trim() === '登录');
    tab?.click(); return !!tab;
  })()`)
  await sleep(400)
  await evaluate(`(() => {
    const pane = ${visiblePaneJs};
    const inputs = pane.querySelectorAll('input');
    const set = (el, v) => { Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })); };
    set(inputs[0], ${JSON.stringify(USER)});
    set(inputs[1], ${JSON.stringify(PASS)});
    return [...inputs].map(i => i.value).join(' | ');
  })()`)
  await evaluate(`(() => {
    const pane = ${visiblePaneJs};
    const btn = [...pane.querySelectorAll('button')].find(b => b.textContent.trim() === '登录');
    btn?.click(); return !!btn;
  })()`)
  await sleep(2500)
  results.phases.afterLoginUrl = await evaluate(`location.pathname + location.search`)
  console.log('[A] 登录后落点: ' + results.phases.afterLoginUrl + '（期望 /settings，验证回跳）')
  await shot('T26-A2-login-redirect-back-settings.png')
  if (results.phases.afterLoginUrl.includes('/login')) {
    throw new Error('登录未成功（仍在 /login），终止以免浪费 LLM 预算；toast=' + results.phases.registerToast)
  }
  save()

  // ── B. 进入聊天页 + 选数据源 ──
  await navigate(`${BASE}/chat`)
  await waitFor(`document.querySelectorAll('.session-item').length > 0 || !!document.querySelector('.session-empty')`, 15000, '会话区就绪')
  await waitFor(`document.querySelector('.ds-selector .el-input__wrapper') !== null`, 15000, '数据源选择器')
  console.log('[B] 数据源选择器现状: ' + await evaluate(`document.querySelector('.ds-selector input')?.value || ''`))
  // 显式选"测试库(25表)"（T12 已验证该库含 users 表 200 行）
  await evaluate(`document.querySelector('.ds-selector .el-input__wrapper')?.click(); 'ok'`)
  await sleep(600)
  console.log('[B] 选择测试库(25表): ' + await evaluate(`(() => {
    const opt = [...document.querySelectorAll('.ds-selector .el-select-dropdown__item')].find(o => o.textContent.includes('测试库(25表)'));
    if (!opt) return 'not-found';
    opt.click(); return 'clicked';
  })()`))
  await sleep(500)
  results.phases.selectedDatasource = await evaluate(`document.querySelector('.ds-selector input')?.value || ''`)
  console.log('[B] 已选数据源: ' + results.phases.selectedDatasource)
  await shot('T26-B0-chat-datasource-selected.png')

  if (STOP_AFTER === '--precheck') {
    console.log('[precheck] 登录 / 数据源选择预检结束（0 次 LLM）')
    results.sseRequests = sseRequests
    save()
    ws.close()
    chrome.kill()
    process.exit(0)
  }

  // ── B1. 真实问答①（真实 LLM）：轨迹面板配对 / 折叠 / 摘要省略 / 展开 ──
  const Q1 = '统计 users 表中共有多少个用户'
  await evaluate(setInputJs('.input-textarea', Q1))
  await sleep(300)
  await evaluate(`document.querySelector('.send-btn')?.click(); 'ok'`)
  console.log('[B1] 已发送问答①: ' + Q1)
  await sleep(1200)
  console.log('[B1] 流式中 loading=' + await evaluate(`!!document.querySelector('.loading-indicator')`))
  console.log('[B1] 流式中停止按钮存在=' + await evaluate(`!!document.querySelector('.stop-btn')`))
  await waitFor(`!document.querySelector('.loading-indicator')`, 180000, '问答①完成')
  await sleep(600)

  results.phases.traceQ1 = await evaluate(`(() => {
    const items = [...document.querySelectorAll('.trace-item')];
    return {
      '条目数(配对后)': items.length,
      steps: items.map(el => ({
        tool: el.querySelector('.trace-tool')?.textContent?.trim(),
        status: el.querySelector('.trace-status')?.textContent?.trim(),
        '入参摘要': el.querySelectorAll('.trace-field')[0]?.querySelector('.trace-text')?.textContent?.trim(),
        '入参title': el.querySelectorAll('.trace-field')[0]?.querySelector('.trace-text')?.getAttribute('title'),
        '结果摘要': el.querySelectorAll('.trace-field')[1]?.querySelector('.trace-text')?.textContent?.trim(),
        '结果title': el.querySelectorAll('.trace-field')[1]?.querySelector('.trace-text')?.getAttribute('title'),
        '摘要单行省略': [...el.querySelectorAll('.trace-text')].every(t => t.scrollWidth > t.clientWidth || t.textContent.trim().length < 60),
        '展开态': el.classList.contains('is-expanded')
      })),
      '答案': [...document.querySelectorAll('.msg-system .message-content')].pop()?.textContent?.trim(),
      '结果行数': document.querySelector('.result-count')?.textContent?.trim() || ''
    };
  })()`)
  console.log('[B1] 轨迹配对结果: ' + JSON.stringify(results.phases.traceQ1.steps.map(s => `${s.tool}/${s.status}`)))
  console.log('[B1] 答案: ' + results.phases.traceQ1['答案'])
  await evaluate(`document.querySelector('.chat-messages').scrollTop = document.querySelector('.chat-messages').scrollHeight; 'ok'`)
  await shot('T26-B1-trace-paired-collapsed.png')

  // 展开第一条：入参 + 结果全文（JSON 美化）
  await evaluate(`document.querySelector('.trace-item .trace-row')?.click(); 'ok'`)
  await sleep(400)
  results.phases.traceExpanded = await evaluate(`(() => {
    const el = document.querySelector('.trace-item');
    return {
      '展开态': el?.classList.contains('is-expanded'),
      '全文块数': el?.querySelectorAll('.trace-pre').length,
      '入参全文前120字': el?.querySelector('.trace-pre')?.textContent?.slice(0, 120)
    };
  })()`)
  console.log('[B1] 展开断言: ' + JSON.stringify({ ...results.phases.traceExpanded, '入参全文前120字': (results.phases.traceExpanded['入参全文前120字'] || '').slice(0, 60) + '…' }))
  await shot('T26-B1b-trace-expanded.png')

  // ── C. 骨架屏：限速 + 新建第二个会话后切换 ──
  await evaluate(`document.querySelector('.new-session-btn')?.click(); 'ok'`)
  await sleep(1200)
  const sessionCount = await evaluate(`document.querySelectorAll('.session-item').length`)
  console.log('[C] 会话数=' + sessionCount)
  await send('Network.emulateNetworkConditions', {
    offline: false, latency: 900, downloadThroughput: 30000, uploadThroughput: 30000, connectionType: 'cellular3g'
  })
  await evaluate(`document.querySelectorAll('.session-item')[1]?.click(); 'ok'`)
  await sleep(250)
  results.phases.skeleton = await evaluate(`(() => ({
    '骨架屏可见': !!document.querySelector('.chat-skeleton .el-skeleton'),
    '骨架行数': document.querySelectorAll('.chat-skeleton .el-skeleton__item').length
  }))()`)
  console.log('[C] 骨架屏断言: ' + JSON.stringify(results.phases.skeleton))
  await shot('T26-C1-skeleton-session-switch.png')
  await send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1, connectionType: 'none' })
  await waitFor(`!document.querySelector('.chat-skeleton')`, 15000, '骨架屏结束')
  save()

  // ── D. 停止生成（真实问答②中途 abort）──
  await evaluate(`document.querySelector('.new-session-btn')?.click(); 'ok'`)
  await sleep(1000)
  const Q2 = '查询年龄大于25岁的用户'
  await evaluate(setInputJs('.input-textarea', Q2))
  await sleep(300)
  await evaluate(`document.querySelector('.send-btn')?.click(); 'ok'`)
  console.log('[D] 已发送问答②: ' + Q2 + '，等待轨迹出现后点停止')
  await waitFor(`document.querySelectorAll('.trace-item').length > 0 || !!document.querySelector('.stop-btn')`, 20000, '流式进行中')
  await sleep(1500)
  results.phases.stopInFlight = await evaluate(`(() => ({
    '加载中': !!document.querySelector('.loading-indicator'),
    '停止按钮': !!document.querySelector('.stop-btn'),
    '已渲染轨迹条目': document.querySelectorAll('.trace-item').length,
    '流式光标': document.querySelectorAll('.stream-cursor').length
  }))()`)
  console.log('[D] 停止前状态: ' + JSON.stringify(results.phases.stopInFlight))
  await shot('T26-D1-streaming-before-stop.png')
  console.log('[D] 点击停止: ' + await evaluate(`(() => {
    const btn = document.querySelector('.stop-btn');
    if (!btn) return 'not-found';
    btn.click(); return 'clicked';
  })()`))
  await sleep(1200)
  results.phases.afterStop = await evaluate(`(() => ({
    '加载中(应为false)': !!document.querySelector('.loading-indicator'),
    '停止按钮(应为false)': !!document.querySelector('.stop-btn'),
    '发送按钮恢复': !!document.querySelector('.send-btn'),
    '发送按钮disabled': document.querySelector('.send-btn')?.disabled,
    '残留流式光标(应为0)': document.querySelectorAll('.stream-cursor').length,
    '气泡停止标记': document.querySelector('.stopped-note')?.textContent?.trim() || '',
    '输入区提示': document.querySelector('.input-hint')?.textContent?.trim() || ''
  }))()`)
  console.log('[D] 停止后状态: ' + JSON.stringify(results.phases.afterStop))
  await shot('T26-D2-after-stop.png')

  // 后端侧核对：该轮 status=cancelled（T25）
  const turnCheckJs = `(async () => {
    const sid = Number(localStorage.getItem('currentSessionId'));
    const token = localStorage.getItem('token');
    for (let i = 0; i < 12; i++) {
      const res = await fetch('/api/v1/sessions/' + sid + '/turns', { headers: { Authorization: 'Bearer ' + token } });
      const body = await res.json();
      const turns = body.data || [];
      const last = turns[turns.length - 1];
      if (last && last.status === 'cancelled') return { sessionId: sid, turns: turns.map(t => ({ id: t.id, question: t.question, status: t.status })), lastStatus: last.status };
      await new Promise(r => setTimeout(r, 500));
    }
    const res = await fetch('/api/v1/sessions/' + sid + '/turns', { headers: { Authorization: 'Bearer ' + token } });
    const body = await res.json();
    return { sessionId: sid, turns: (body.data || []).map(t => ({ id: t.id, question: t.question, status: t.status })), lastStatus: (body.data || []).pop()?.status };
  })()`
  results.phases.stopTurnStatus = await evaluate(turnCheckJs)
  console.log('[D] 会话轮次(status): ' + JSON.stringify(results.phases.stopTurnStatus))
  save()

  // 刷新后历史回显：cancelled 轮次带"已停止生成"标记
  await navigate(`${BASE}/chat`)
  await waitFor(`document.querySelectorAll('.session-item').length > 0`, 15000, '刷新后会话列表')
  await waitFor(`document.querySelectorAll('.msg-system').length > 0`, 15000, '刷新后历史')
  results.phases.stopAfterReload = await evaluate(`(() => ({
    '停止标记': [...document.querySelectorAll('.stopped-note')].map(e => e.textContent.trim()),
    '历史消息': [...document.querySelectorAll('.message-bubble .message-content')].map(e => e.textContent.trim().slice(0, 30))
  }))()`)
  console.log('[D] 刷新后历史: ' + JSON.stringify(results.phases.stopAfterReload))
  await evaluate(`document.querySelector('.chat-messages').scrollTop = document.querySelector('.chat-messages').scrollHeight; 'ok'`)
  await shot('T26-D3-reload-shows-cancelled.png')

  // ── E. 错误态 + 重试 ──
  await evaluate(`document.querySelector('.new-session-btn')?.click(); 'ok'`)
  await sleep(1000)
  // 一次性改写：本页下一次 SSE 请求 datasourceId → 99999（不存在 → 真实 4001 响应），之后自动失效
  console.log('[E] 安装一次性 datasourceId 改写: ' + await evaluate(`(() => {
    const orig = window.fetch;
    window.fetch = (input, init) => {
      const url = typeof input === 'string' ? input : input.url;
      if (window.__t26RewriteDs && url.includes('/api/v1/chat/stream')) {
        window.__t26RewriteDs = false;
        return orig(url.replace(/datasourceId=\\d+/, 'datasourceId=99999'), init);
      }
      return orig(input, init);
    };
    window.__t26RewriteDs = true;
    return 'installed';
  })()`))
  const Q3 = '查询所有用户'
  await evaluate(setInputJs('.input-textarea', Q3))
  await sleep(300)
  await evaluate(`document.querySelector('.send-btn')?.click(); 'ok'`)
  console.log('[E] 已发送(被改写到不存在数据源 99999): ' + Q3)
  await waitFor(`!!document.querySelector('.error-bubble')`, 20000, '错误气泡')
  const sseCountBefore = sseRequests.length
  results.phases.errorBubble = await evaluate(`(() => ({
    '错误文本': document.querySelector('.error-text')?.textContent?.trim() || '',
    '重试按钮': document.querySelector('.error-retry-btn')?.textContent?.trim() || '',
    '加载中(应为false)': !!document.querySelector('.loading-indicator')
  }))()`)
  console.log('[E] 错误气泡: ' + JSON.stringify(results.phases.errorBubble))
  console.log('[E] 触发错误的请求 URL: ' + (sseRequests[sseCountBefore - 1] || 'n/a'))
  await shot('T26-E1-error-bubble.png')

  // 点重试 → 真实问答③（同一问题、同一会话）
  const sessionIdBeforeRetry = await evaluate(`Number(localStorage.getItem('currentSessionId'))`)
  console.log('[E] 点击重试: ' + await evaluate(`(() => {
    const btn = document.querySelector('.error-retry-btn');
    if (!btn) return 'not-found';
    btn.click(); return 'clicked';
  })()`))
  await waitFor(`!!document.querySelector('.loading-indicator')`, 10000, '重试已发出')
  await waitFor(`!document.querySelector('.loading-indicator')`, 180000, '重试完成')
  await sleep(600)
  results.phases.retry = await evaluate(`(() => ({
    '错误气泡已移除': !document.querySelector('.error-bubble'),
    '轨迹条目': [...document.querySelectorAll('.trace-item .trace-tool')].map(e => e.textContent.trim()),
    '答案': [...document.querySelectorAll('.msg-system .message-content')].pop()?.textContent?.trim(),
    '用户消息条数': [...document.querySelectorAll('.msg-user .message-content')].map(e => e.textContent.trim())
  }))()`)
  results.phases.retrySessionSame = sessionIdBeforeRetry === await evaluate(`Number(localStorage.getItem('currentSessionId'))`)
  console.log('[E] 重试结果: ' + JSON.stringify(results.phases.retry) + ' 会话未变=' + results.phases.retrySessionSame)
  console.log('[E] 重试请求 URL: ' + (sseRequests[sseRequests.length - 1] || 'n/a'))
  await evaluate(`document.querySelector('.chat-messages').scrollTop = document.querySelector('.chat-messages').scrollHeight; 'ok'`)
  await shot('T26-E2-after-retry-success.png')
  save()

  // ── F. 403：伪造 token → 轻提示 + 跳登录（带 redirect 回跳参数）──
  await evaluate(`localStorage.setItem('token', 'forged.t26.token'); 'ok'`)
  await evaluate(setInputJs('.input-textarea', '这条不会真正执行'))
  await sleep(200)
  await evaluate(`document.querySelector('.send-btn')?.click(); 'ok'`)
  await sleep(900)
  results.phases.forbidden = await evaluate(`(() => ({
    'URL': location.pathname + location.search,
    '提示toast': document.querySelector('.el-message')?.textContent?.trim() || ''
  }))()`)
  console.log('[F] 403 后: ' + JSON.stringify(results.phases.forbidden))
  await shot('T26-F1-403-toast-login-redirect.png')
} catch (error) {
  results.error = String(error && error.message || error)
  console.log('[error] ' + results.error)
} finally {
  results.sseRequests = sseRequests
  results.finishedAt = new Date().toISOString()
  save()
  console.log('[done] 证据已写入 ' + OUT + '/t26-results.json（SSE 请求共 ' + sseRequests.length + ' 条）')
  ws.close()
  chrome.kill()
  process.exit(0)
}
