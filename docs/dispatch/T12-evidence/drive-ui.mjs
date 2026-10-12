/**
 * T12 全链路 UI 实测驱动脚本（零依赖：Node 20 --experimental-websocket + Chrome CDP）。
 *
 * 用法：
 *   node --experimental-websocket drive-ui.mjs <token> <username>
 *
 * 步骤（每步截图到本目录）：
 *   1. 打开 /login（旧状态清空）→ 注入真实登录 token（等价于登录成功后的 localStorage 状态）
 *   2. 打开 /chat：侧栏会话列表（curl 阶段产生）、数据源选择器
 *   3. 点击会话 → 历史问答回显
 *   4. 提问（真实 LLM）：流式中截图 + 完成后截图（回答 + 表格 + 轨迹）
 *   5. 记录 SSE 网络请求 URL（取证 datasourceId / sessionId）
 *   6. 刷新页面 → 会话侧栏 + 历史回显
 *   7. 表结构抽屉（metadata 端点，展开一张表看列）
 *   8. 设置页数据源列表
 */
import { spawn } from 'node:child_process'
import { mkdtempSync, writeFileSync, mkdirSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const TOKEN = process.argv[2]
const USERNAME = process.argv[3]
const MOCK = process.argv[4] === '--mock'
const OUT = 'F:/FANG_ZHENG/nl2sql/docs/dispatch/T12-evidence'
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9333
mkdirSync(OUT, { recursive: true })

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const profile = mkdtempSync(join(tmpdir(), 't12-chrome-'))
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
    } catch {
      // chrome 未就绪
    }
    await sleep(250)
  }
  throw new Error('Chrome CDP 未就绪')
}

const wsUrl = await findTarget()
const ws = new WebSocket(wsUrl)
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
      sseRequests.push(url)
      console.log('[net] SSE 请求: ' + decodeURIComponent(url))
    }
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
    throw new Error('页面脚本异常: ' + JSON.stringify(result.exceptionDetails.exception?.description || result.exceptionDetails))
  }
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

async function navigate(url) {
  await send('Page.navigate', { url })
  await sleep(1200)
}

await send('Page.enable')
await send('Runtime.enable')
await send('Network.enable')

// ── 1. 登录态注入（真实 token；前端登录成功后的状态等价） ──
await navigate('http://localhost:5173/login')
await evaluate(`localStorage.setItem('token', ${JSON.stringify(TOKEN)});
  localStorage.setItem('username', ${JSON.stringify(USERNAME)});
  localStorage.removeItem('currentSessionId'); 'ok'`)
await shot('shot-01-login.png')

// ── 2. 进入聊天页：侧栏会话 + 数据源选择器 ──
await navigate('http://localhost:5173/chat')
await waitFor(`document.querySelectorAll('.session-item').length > 0`, 20000, '会话列表')
console.log('[ui] 侧栏会话数=' + await evaluate(`document.querySelectorAll('.session-item').length`))
console.log('[ui] 首条会话=' + await evaluate(`document.querySelector('.session-item .session-title').textContent`))
await shot('shot-02-chat-sidebar.png')

// ── 3. 点击会话 → 历史问答回显 ──
console.log('[ui] 点击会话: ' + await evaluate(`(() => {
  const target = document.querySelector('.session-item');
  if (!target) return 'not-found';
  target.click(); return 'clicked';
})()`))
await waitFor(`document.querySelectorAll('.message-bubble .message-content').length >= 4`, 15000, '历史消息渲染')
console.log('[ui] 历史消息条数=' + await evaluate(`document.querySelectorAll('.message-bubble .message-content').length`))
console.log('[ui] 选中的数据源显示=' + await evaluate(`document.querySelector('.ds-selector input')?.value`))
await shot('shot-03-history-restore.png')

// ── 4. 提问（真实问答；--mock 模式用 q1.sse 真实帧做零成本渲染预检） ──
if (MOCK) {
  const frames = readFileSync(join(OUT, 'q1.sse'), 'utf-8')
  await evaluate(`(() => {
    const raw = ${JSON.stringify(frames)};
    const chunks = raw.split(/\\r?\\n\\r?\\n/).filter(s => s.trim()).map(f => f + '\\n\\n');
    const origFetch = window.fetch;
    window.fetch = async (input, init) => {
      const url = typeof input === 'string' ? input : input.url;
      if (url.includes('/api/v1/chat/stream')) {
        const stream = new ReadableStream({
          async start(controller) {
            const enc = new TextEncoder();
            for (const c of chunks) {
              controller.enqueue(enc.encode(c));
              await new Promise(r => setTimeout(r, 200));
            }
            controller.close();
          }
        });
        return new Response(stream, { status: 200, headers: { 'content-type': 'text/event-stream' } });
      }
      return origFetch(input, init);
    };
    return 'mock-fetch-installed ' + chunks.length + ' frames';
  })()`)
  console.log('[mock] fetch 已替换为真实捕获帧回放')
}

console.log('[ui] 填入问题: ' + await evaluate(`(() => {
  const ta = document.querySelector('.input-textarea');
  const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
  setter.call(ta, ${JSON.stringify(MOCK ? '预检问题（mock SSE 回放）' : 'orders 表中共有多少条订单记录？')});
  ta.dispatchEvent(new Event('input', { bubbles: true }));
  return ta.value;
})()`))
await sleep(400)
console.log('[ui] 点击发送: ' + await evaluate(`(() => {
  const btn = document.querySelector('.send-btn');
  if (btn.disabled) return 'still-disabled';
  btn.click(); return 'clicked';
})()`))
await sleep(1000)
console.log('[ui] 流式中(loading)=' + await evaluate(`!!document.querySelector('.loading-indicator')`))
console.log('[ui] 流式中已渲染轨迹行=' + await evaluate(`document.querySelectorAll('.trace-item').length`))
await shot(MOCK ? 'precheck-04-mock-streaming.png' : 'shot-04-streaming.png')

await waitFor(`!document.querySelector('.loading-indicator')`, 180000, '问答完成')
await sleep(600)
const summary = await evaluate(`(() => {
  const bubbles = [...document.querySelectorAll('.message-bubble .message-content')];
  const last = bubbles[bubbles.length - 1]?.textContent?.trim();
  const traceRows = [...document.querySelectorAll('.trace-item')].map(el => el.textContent.replace(/\\s+/g, ' ').trim());
  const tableHeaders = [...document.querySelectorAll('.result-table-wrapper th .cell')].map(el => el.textContent.trim());
  const tableCells = [...document.querySelectorAll('.result-table-wrapper td .cell')].map(el => el.textContent.trim());
  const sql = document.querySelectorAll('.sql-code');
  return {
    answer: last,
    traceRows,
    tableHeaders,
    tableFirstRows: tableCells.slice(0, 6),
    sql: sql[sql.length - 1]?.textContent?.trim(),
    resultCount: document.querySelector('.result-count')?.textContent?.trim()
  };
})()`)
writeFileSync(join(OUT, 'ui-q3-render.json'), JSON.stringify(summary, null, 2))
console.log('[ui] 回答=' + summary.answer)
console.log('[ui] 轨迹行数=' + summary.traceRows.length)
console.log('[ui] 表格列=' + JSON.stringify(summary.tableHeaders) + ' 行=' + JSON.stringify(summary.tableFirstRows))
await evaluate(`document.querySelector('.chat-messages').scrollTop = document.querySelector('.chat-messages').scrollHeight; 'ok'`)
await shot(MOCK ? 'precheck-05-mock-render.png' : 'shot-05-answer-table-trace.png')

if (MOCK) {
  console.log('[mock] 渲染预检结束（未消耗真实 LLM），结果: ' + JSON.stringify(summary))
  ws.close()
  chrome.kill()
  process.exit(0)
}

// ── 5. 刷新 → 侧栏 + 历史回显 ──
await navigate('http://localhost:5173/chat')
await waitFor(`document.querySelectorAll('.session-item').length > 0`, 20000, '刷新后会话列表')
await sleep(1500)
await waitFor(`document.querySelectorAll('.message-bubble .message-content').length >= 6`, 20000, '刷新后历史回显')
const afterReload = await evaluate(`(() => {
  const item = document.querySelector('.session-item');
  return {
    sessionTitle: item?.querySelector('.session-title')?.textContent?.trim(),
    turnCount: item?.querySelector('.session-meta')?.textContent?.replace(/\\s+/g, ' ').trim(),
    bubbles: [...document.querySelectorAll('.message-bubble .message-content')].map(el => el.textContent.trim().slice(0, 40))
  };
})()`)
writeFileSync(join(OUT, 'ui-after-reload.json'), JSON.stringify(afterReload, null, 2))
console.log('[ui] 刷新后会话=' + JSON.stringify(afterReload, null, 0))
await shot('shot-06-after-reload-history.png')

// ── 6. 表结构抽屉（metadata 端点） ──
console.log('[ui] 打开表结构: ' + await evaluate(`(() => {
  const btn = [...document.querySelectorAll('.chat-header button')].find(b => b.textContent.includes('表结构'));
  if (!btn) return 'not-found';
  btn.click(); return 'clicked';
})()`))
await waitFor(`document.querySelectorAll('.el-drawer .schema-tree .el-tree-node').length > 0`, 15000, '表结构树')
const tableCount = await evaluate(`document.querySelectorAll('.el-drawer .schema-tree > .el-tree-node').length`)
console.log('[ui] 表节点数=' + tableCount)
console.log('[ui] 展开第一张表: ' + await evaluate(`(() => {
  const node = document.querySelector('.el-drawer .schema-tree .el-tree-node__content');
  if (!node) return 'not-found';
  node.click(); return 'clicked';
})()`))
await sleep(2000)
console.log('[ui] 展开后列节点=' + await evaluate(`document.querySelectorAll('.el-drawer .schema-tree .el-tree-node__children .el-tree-node').length`))
await shot('shot-07-schema-drawer.png')

// ── 7. 设置页数据源列表 ──
await navigate('http://localhost:5173/settings')
await waitFor(`document.querySelectorAll('.el-table__row').length > 0`, 15000, '数据源表格')
console.log('[ui] 设置页数据源行数=' + await evaluate(`document.querySelectorAll('.el-table__row').length`))
console.log('[ui] 操作按钮=' + await evaluate(`[...document.querySelectorAll('.el-table__row:first-child .action-btn')].map(b => b.textContent.replace(/\\s+/g,'').trim()).join(',')`))
await shot('shot-08-settings-datasources.png')

writeFileSync(join(OUT, 'sse-requests.json'), JSON.stringify(sseRequests.map(decodeURIComponent), null, 2))
console.log('[done] SSE 请求共 ' + sseRequests.length + ' 条')
ws.close()
chrome.kill()
process.exit(0)
