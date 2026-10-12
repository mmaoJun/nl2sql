/**
 * T12 联调修复复跑（7B 行为问题的唯一一次修复）：在**全新会话**里复跑浏览器全链路。
 *
 * 背景：session 180 的历史被 curl（Git Bash argv 编码）写坏成 U+FFFD 乱码，
 * 7B 带着污染历史对干净问题也回了面板化拒答。本脚本走用户真实路径：
 * 显式选 nlp2sql_test 数据源 → 新建对话（POST /sessions）→ 提问 → 流式渲染 → 刷新回显。
 *
 * 用法：node --experimental-websocket fix-run.mjs <token> <username>
 */
import { spawn } from 'node:child_process'
import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const TOKEN = process.argv[2]
const USERNAME = process.argv[3]
const OUT = 'F:/FANG_ZHENG/nl2sql/docs/dispatch/T12-evidence'
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9334

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const profile = mkdtempSync(join(tmpdir(), 't12-chrome-fix-'))
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
    } catch { /* not ready */ }
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
  if (msg.method === 'Network.requestWillBeSent' && msg.params.request.url.includes('/api/v1/chat/stream')) {
    sseRequests.push(msg.params.request.url)
    console.log('[net] SSE 请求: ' + decodeURIComponent(msg.params.request.url))
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
  if (result.exceptionDetails) throw new Error('页面脚本异常: ' + JSON.stringify(result.exceptionDetails))
  return result.result.value
}

async function waitFor(expression, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await evaluate(expression)) return true
    await sleep(400)
  }
  throw new Error(`等待超时: ${label}`)
}

async function shot(name) {
  const { data } = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(join(OUT, name), Buffer.from(data, 'base64'))
  console.log('[shot] ' + name)
}

await send('Page.enable')
await send('Runtime.enable')
await send('Network.enable')

// 登录态注入
await send('Page.navigate', { url: 'http://localhost:5173/login' })
await sleep(1200)
await evaluate(`localStorage.setItem('token', ${JSON.stringify(TOKEN)});
  localStorage.setItem('username', ${JSON.stringify(USERNAME)});
  localStorage.removeItem('currentSessionId'); 'ok'`)

// 进入聊天页：侧栏既有 1 个会话
await send('Page.navigate', { url: 'http://localhost:5173/chat' })
await waitFor(`document.querySelectorAll('.session-item').length > 0`, 20000, '会话列表')
await sleep(800)

// 显式选择 nlp2sql_test 数据源（下拉第 5 项，id=5）
console.log('[ui] 打开数据源下拉: ' + await evaluate(`(() => {
  const el = document.querySelector('.ds-selector .el-select__wrapper') || document.querySelector('.ds-selector .el-input__wrapper');
  if (!el) return 'not-found';
  el.click(); return 'clicked';
})()`))
await sleep(600)
console.log('[ui] 下拉项: ' + await evaluate(`[...document.querySelectorAll('.ds-selector .el-select-dropdown__item')].map(el => el.textContent.trim()).join(' | ')`))
console.log('[ui] 选择测试库(25表): ' + await evaluate(`(() => {
  const opt = [...document.querySelectorAll('.ds-selector .el-select-dropdown__item')].find(el => el.textContent.includes('测试库'));
  if (!opt) return 'not-found';
  opt.click(); return 'clicked';
})()`))
await sleep(500)
console.log('[ui] 当前选中数据源: ' + await evaluate(`document.querySelector('.ds-selector .el-select__selected-item')?.textContent?.trim()`))
await shot('shot-09-datasource-selected.png')

// 新建对话（POST /api/v1/sessions）
console.log('[ui] 点击新建对话: ' + await evaluate(`document.querySelector('.new-session-btn').click() || 'clicked'`))
await waitFor(`document.querySelectorAll('.session-item').length === 2`, 15000, '新会话出现在侧栏')
await sleep(600)
console.log('[ui] 新建后首条会话标题: ' + await evaluate(`document.querySelector('.session-item .session-title')?.textContent?.trim()`))
console.log('[ui] 消息区条数(应为空态/0): ' + await evaluate(`document.querySelectorAll('.message-bubble').length`))

// 提问（全新会话，无污染历史）
await evaluate(`(() => {
  const ta = document.querySelector('.input-textarea');
  const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
  setter.call(ta, 'orders 表中共有多少条订单记录？');
  ta.dispatchEvent(new Event('input', { bubbles: true }));
  return 'ok';
})()`)
await sleep(400)
console.log('[ui] 点击发送: ' + await evaluate(`(() => {
  const btn = document.querySelector('.send-btn');
  if (btn.disabled) return 'still-disabled';
  btn.click(); return 'clicked';
})()`))
await sleep(1200)
console.log('[ui] 流式中(loading)=' + await evaluate(`!!document.querySelector('.loading-indicator')`))
console.log('[ui] 流式中已渲染轨迹行=' + await evaluate(`document.querySelectorAll('.trace-item').length`))
await shot('shot-10-streaming.png')

await waitFor(`!document.querySelector('.loading-indicator')`, 180000, '问答完成')
await sleep(600)
const summary = await evaluate(`(() => {
  const bubbles = [...document.querySelectorAll('.message-bubble .message-content')];
  const traceRows = [...document.querySelectorAll('.trace-item')].map(el => el.textContent.replace(/\\s+/g, ' ').trim());
  const tableHeaders = [...document.querySelectorAll('.result-table-wrapper th .cell')].map(el => el.textContent.trim());
  const tableCells = [...document.querySelectorAll('.result-table-wrapper td .cell')].map(el => el.textContent.trim());
  const sqls = [...document.querySelectorAll('.sql-code')];
  return {
    answer: bubbles[bubbles.length - 1]?.textContent?.trim(),
    traceRows, tableHeaders, tableFirstRows: tableCells.slice(0, 8),
    sql: sqls[sqls.length - 1]?.textContent?.trim(),
    resultCount: document.querySelector('.result-count')?.textContent?.trim(),
    metas: [...document.querySelectorAll('.result-meta .meta-item')].map(el => el.textContent.trim())
  };
})()`)
writeFileSync(join(OUT, 'ui-fix-render.json'), JSON.stringify(summary, null, 2))
console.log('[ui] 回答=' + summary.answer)
console.log('[ui] 轨迹行数=' + summary.traceRows.length + ' 表格列=' + JSON.stringify(summary.tableHeaders) + ' 数据行=' + JSON.stringify(summary.tableFirstRows) + ' 计数=' + summary.resultCount)
await evaluate(`document.querySelector('.chat-messages').scrollTop = document.querySelector('.chat-messages').scrollHeight; 'ok'`)
await shot('shot-11-answer-table-trace.png')

// 刷新 → 会话侧栏 + 历史回显（新会话应排第一）
await send('Page.navigate', { url: 'http://localhost:5173/chat' })
await waitFor(`document.querySelectorAll('.session-item').length === 2`, 20000, '刷新后会话列表')
await sleep(1500)
await waitFor(`document.querySelectorAll('.message-bubble .message-content').length === 2`, 20000, '刷新后历史回显')
const afterReload = await evaluate(`(() => {
  const first = document.querySelector('.session-item');
  return {
    sessionTitle: first?.querySelector('.session-title')?.textContent?.trim(),
    turnCount: first?.querySelector('.session-meta')?.textContent?.replace(/\\s+/g, ' ').trim(),
    dsSelected: document.querySelector('.ds-selector .el-select__selected-item')?.textContent?.trim(),
    bubbles: [...document.querySelectorAll('.message-bubble .message-content')].map(el => el.textContent.trim().slice(0, 60))
  };
})()`)
writeFileSync(join(OUT, 'ui-fix-after-reload.json'), JSON.stringify(afterReload, null, 2))
console.log('[ui] 刷新后：会话=' + afterReload.sessionTitle + ' | ' + afterReload.turnCount + ' | 数据源=' + afterReload.dsSelected)
console.log('[ui] 刷新后气泡=' + JSON.stringify(afterReload.bubbles))
await shot('shot-12-after-reload-history.png')

writeFileSync(join(OUT, 'sse-requests-fix.json'), JSON.stringify(sseRequests.map(decodeURIComponent), null, 2))
console.log('[done] SSE 请求共 ' + sseRequests.length + ' 条')
ws.close()
chrome.kill()
process.exit(0)
