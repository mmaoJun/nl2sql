<template>
  <div class="login-page">
    <!-- 左栏：品牌 + 能力点 + 「自然语言 → SQL → 结果」演示动画 -->
    <aside class="hero">
      <div class="hero-grid"></div>
      <div class="hero-glow hero-glow-1"></div>
      <div class="hero-glow hero-glow-2"></div>

      <div class="hero-inner">
        <div class="hero-brand">
          <span class="hero-logo">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M4 7h16M4 12h10M4 17h6" stroke-linecap="round" />
            </svg>
          </span>
          <span class="hero-name">NL2SQL</span>
        </div>

        <h1 class="hero-title">用自然语言<br />查询你的数据库</h1>
        <p class="hero-sub">不用记表名和字段，把想问的直接问出来。</p>

        <ul class="hero-points">
          <li v-for="p in points" :key="p.text">
            <el-icon :size="15"><component :is="p.icon" /></el-icon>
            <span>{{ p.text }}</span>
          </li>
        </ul>

        <div class="hero-logos">
          <span v-for="b in brandTiles" :key="b.key" class="hero-logo-tile" :title="b.title">
            <svg :viewBox="b.viewBox" fill="currentColor" aria-hidden="true"><path :d="b.path" /></svg>
          </span>
        </div>

        <div class="demo" :class="{ 'dim-ask': showSql, 'show-sql': showSql, 'show-table': showTable }" aria-hidden="true">
          <div class="demo-ask">
            <span>{{ typedAsk }}</span>
            <span class="demo-caret"></span>
          </div>

          <div class="demo-sql">
            <span class="demo-tag">SQL</span>
            <code>{{ typedSql }}</code>
          </div>

          <div class="demo-table">
            <div class="demo-row demo-row-head">
              <span class="demo-cell">姓名</span>
              <span class="demo-cell">年龄</span>
              <span class="demo-cell">部门</span>
            </div>
            <div
              v-for="(row, i) in DEMO_ROWS"
              :key="i"
              class="demo-row"
              :style="{ '--i': i }"
            >
              <span v-for="(cell, j) in row" :key="j" class="demo-cell">{{ cell }}</span>
            </div>
          </div>

          <div class="demo-meta">共 10 行 · 耗时 12 ms</div>
        </div>
      </div>
    </aside>

    <!-- 右栏：登录 / 注册 -->
    <main class="panel">
      <div class="auth">
        <div class="auth-brand">
          <span class="auth-brand-logo">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M4 7h16M4 12h10M4 17h6" stroke-linecap="round" />
            </svg>
          </span>
          <span>NL2SQL</span>
        </div>

        <h2 class="auth-title">{{ isLogin ? '欢迎回来' : '创建账号' }}</h2>
        <p class="auth-switch">
          <template v-if="isLogin">
            还没有账号？<button type="button" @click="switchMode('register')">去注册</button>
          </template>
          <template v-else>
            已有账号？<button type="button" @click="switchMode('login')">去登录</button>
          </template>
        </p>

        <el-form :model="form" label-position="top" class="auth-form" @submit.prevent="handleSubmit">
          <el-form-item label="用户名">
            <el-input v-model.trim="form.username" size="large" placeholder="请输入用户名" autocomplete="username" />
          </el-form-item>

          <el-form-item label="密码">
            <el-input
              v-model="form.password"
              type="password"
              size="large"
              show-password
              placeholder="请输入密码"
              :autocomplete="isLogin ? 'current-password' : 'new-password'"
            />
          </el-form-item>

          <el-form-item v-if="!isLogin" label="确认密码">
            <el-input
              v-model="form.confirmPassword"
              type="password"
              size="large"
              show-password
              placeholder="请再次输入密码"
              autocomplete="new-password"
            />
          </el-form-item>

          <el-button
            type="primary"
            class="submit"
            native-type="submit"
            :loading="loading"
          >
            {{ isLogin ? '登 录' : '注 册' }}
          </el-button>
        </el-form>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { MagicStick, Coin, Lock } from '@element-plus/icons-vue'
import { login, register } from '../api/endpoints'
import { BRAND_ICONS } from '../assets/brandIcons'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const mode = ref('login')
const isLogin = computed(() => mode.value === 'login')

const form = reactive({ username: '', password: '', confirmPassword: '' })

const points = [
  { icon: MagicStick, text: '自然语言直出 SQL，自动理解表结构' },
  { icon: Coin, text: '多数据源统一接入：MySQL / PostgreSQL / Hive / HDFS / REST / GraphQL' },
  { icon: Lock, text: '只读安全：SQL 白名单校验 + 全链路审计' }
]

/** 复用数据源页那套真实品牌图标（白底方块），顺带当作「支持的数据源」展示 */
const brandTiles = ['mysql', 'postgresql', 'hive', 'hdfs', 'rest', 'graphql']
  .map((key) => ({ key, ...(BRAND_ICONS[key] || {}) }))
  .filter((b) => b.path)

/* ---------------- 演示动画：自然语言 → SQL → 结果表 ---------------- */

const ASK = '查询年龄大于 25 岁的用户'
const SQL = 'SELECT * FROM users WHERE age > 25 LIMIT 10'
const DEMO_ROWS = [
  ['张三', '28', '研发部'],
  ['李四', '31', '市场部'],
  ['王五', '26', '研发部'],
  ['赵六', '33', '财务部']
]

const reducedMotion =
  typeof window !== 'undefined' && window.matchMedia
    ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
    : false

const typedAsk = ref('')
const typedSql = ref('')
const phase = ref(0)
const showSql = computed(() => phase.value >= 1)
const showTable = computed(() => phase.value >= 2)

let timers = []

function later(fn, ms) {
  timers.push(setTimeout(fn, ms))
}

function clearTimers() {
  timers.forEach(clearTimeout)
  timers = []
}

/**
 * 一个循环播放的「打字 → 出 SQL → 出行」小剧场。
 *
 * 用一串 setTimeout 串起来而不是纯 CSS 动画：中文逐字打字用 CSS 的 ch/steps
 * 需要按字符宽度凑数（中文字宽约 2ch），很难对齐；交给 JS 逐字补最直观，
 * CSS 只负责 opacity/transform 过渡。每个 run 开头先清一遍上轮定时器，
 * 关闭动画偏好时直接落到最后一帧、不循环。
 */
function runDemo() {
  clearTimers()

  if (reducedMotion) {
    typedAsk.value = ASK
    typedSql.value = SQL
    phase.value = 3
    return
  }

  phase.value = 0
  typedAsk.value = ''
  typedSql.value = ''

  ;[...ASK].forEach((ch, i) => later(() => { typedAsk.value += ch }, 320 + i * 72))

  const afterAsk = 320 + ASK.length * 72 + 460
  later(() => {
    phase.value = 1
    ;[...SQL].forEach((ch, i) => later(() => { typedSql.value += ch }, i * 16))
  }, afterAsk)

  const afterSql = afterAsk + SQL.length * 16 + 300
  later(() => { phase.value = 2 }, afterSql)

  const doneAt = afterSql + DEMO_ROWS.length * 90 + 1500
  later(() => { phase.value = 3 }, doneAt)
  later(runDemo, doneAt + 800)
}

onMounted(runDemo)
onUnmounted(clearTimers)

/* ---------------- 登录 / 注册 ---------------- */

function switchMode(next) {
  mode.value = next
  // 切换时清掉密码，不把上一个表单的密码带过去；用户名保留方便继续
  form.password = ''
  form.confirmPassword = ''
}

/** 登录后回跳原路由（router 守卫 / 403 拦截器会带 redirect query）；
    只接受站内绝对路径，排除 // 开头的协议相对地址，避免开放重定向 */
function resolveRedirect() {
  const target = typeof route.query.redirect === 'string' ? route.query.redirect : ''
  return target.startsWith('/') && !target.startsWith('//') ? target : '/chat'
}

async function handleSubmit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请填写用户名和密码')
    return
  }
  if (!isLogin.value && form.password !== form.confirmPassword) {
    ElMessage.warning('两次密码不一致')
    return
  }

  loading.value = true
  try {
    if (isLogin.value) {
      const res = await login({ username: form.username, password: form.password })
      localStorage.setItem('token', res.data.token)
      localStorage.setItem('username', res.data.username)
      ElMessage.success('登录成功')
      router.push(resolveRedirect())
    } else {
      await register({ username: form.username, password: form.password })
      ElMessage.success('注册成功，请登录')
      switchMode('login')
    }
  } catch (e) {
    // 错误提示由 client.js 拦截器统一弹出
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  background: var(--surface);
}

/* ================= 左栏 ================= */
.hero {
  position: relative;
  flex: 0 0 48%;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 56px 48px;
  overflow: hidden;
  background: #17181a;
  color: #fff;
}

.hero-grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.045) 1px, transparent 1px);
  background-size: 46px 46px;
  mask-image: radial-gradient(circle at 50% 45%, #000 35%, transparent 78%);
}

.hero-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  pointer-events: none;
}

.hero-glow-1 {
  width: 460px;
  height: 460px;
  top: -180px;
  right: -140px;
  background: rgba(255, 255, 255, 0.12);
}

.hero-glow-2 {
  width: 380px;
  height: 380px;
  bottom: -160px;
  left: -120px;
  background: rgba(255, 255, 255, 0.07);
}

.hero-inner {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 470px;
  /* 窗口不高时让左栏内容自己滚，避免演示卡被裁掉看不到 */
  max-height: 100%;
  overflow-y: auto;
  scrollbar-width: none;
}

.hero-inner::-webkit-scrollbar {
  width: 0;
}

.hero-brand {
  display: flex;
  align-items: center;
  gap: 9px;
}

.hero-logo {
  width: 28px;
  height: 28px;
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.14);
  display: flex;
  align-items: center;
  justify-content: center;
}

.hero-name {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.01em;
}

.hero-title {
  margin: 26px 0 10px;
  font-size: 32px;
  line-height: 1.28;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.hero-sub {
  font-size: 13.5px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.58);
}

.hero-points {
  list-style: none;
  margin: 24px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 11px;
}

.hero-points li {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  font-size: 13px;
  line-height: 1.6;
  color: rgba(255, 255, 255, 0.76);
}

.hero-points li .el-icon {
  margin-top: 3px;
  flex-shrink: 0;
  color: rgba(255, 255, 255, 0.55);
}

.hero-logos {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 22px;
}

/* 品牌 logo 统一白底（与数据源列表一致） */
.hero-logo-tile {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: #fff;
  color: var(--text);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.28);
}

.hero-logo-tile svg {
  width: 16px;
  height: 16px;
}

/* ---- 演示动画 ---- */
.demo {
  margin-top: 30px;
  padding: 16px 18px;
  min-height: 212px;
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, 0.09);
  background: rgba(255, 255, 255, 0.045);
  font-family: var(--font-mono);
  font-size: 12.5px;
  line-height: 1.75;
}

.demo-ask {
  color: rgba(255, 255, 255, 0.92);
  transition: opacity 0.35s ease;
}

.demo.dim-ask .demo-ask {
  opacity: 0.34;
}

.demo-caret {
  display: inline-block;
  width: 7px;
  height: 13px;
  margin-left: 3px;
  vertical-align: -2px;
  background: rgba(255, 255, 255, 0.85);
  animation: demo-blink 1s steps(1, end) infinite;
}

.demo.show-sql .demo-caret {
  animation: none;
  opacity: 0;
}

@keyframes demo-blink {
  50% { opacity: 0; }
}

.demo-sql {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 10px;
  opacity: 0;
  transform: translateY(6px);
  transition: opacity 0.35s ease, transform 0.35s ease;
}

.demo.show-sql .demo-sql {
  opacity: 1;
  transform: none;
}

.demo-tag {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 5px;
  font-size: 10px;
  letter-spacing: 0.06em;
  color: rgba(255, 255, 255, 0.62);
  background: rgba(255, 255, 255, 0.1);
}

.demo-sql code {
  color: rgba(255, 255, 255, 0.82);
  word-break: break-all;
}

.demo-table {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed rgba(255, 255, 255, 0.13);
}

.demo-row {
  display: grid;
  grid-template-columns: 1fr 0.7fr 1.2fr;
  gap: 10px;
  opacity: 0;
  transform: translateY(4px);
  transition: opacity 0.3s ease, transform 0.3s ease;
}

.demo.show-table .demo-row {
  opacity: 1;
  transform: none;
  transition-delay: calc(var(--i, 0) * 90ms);
}

.demo-row-head {
  color: rgba(255, 255, 255, 0.42);
}

.demo-cell {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.demo-meta {
  margin-top: 12px;
  font-size: 11.5px;
  color: rgba(255, 255, 255, 0.42);
  opacity: 0;
  transition: opacity 0.3s ease 0.4s;
}

.demo.show-table .demo-meta {
  opacity: 1;
}

@media (prefers-reduced-motion: reduce) {
  .demo-caret { animation: none; }
}

/* ================= 右栏 ================= */
.panel {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 32px;
  background: var(--surface);
}

.auth {
  width: 100%;
  max-width: 368px;
}

.auth-brand {
  display: none;
  align-items: center;
  gap: 8px;
  margin-bottom: 28px;
  font-size: 14px;
  font-weight: 700;
  color: var(--text);
}

.auth-brand-logo {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: var(--primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.auth-title {
  font-size: 27px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--text);
}

.auth-switch {
  margin-top: 8px;
  margin-bottom: 30px;
  font-size: 13.5px;
  color: var(--text-secondary);
}

.auth-switch button {
  border: none;
  background: transparent;
  padding: 0;
  font-family: var(--font-sans);
  font-size: 13.5px;
  color: var(--primary);
  font-weight: 600;
  cursor: pointer;
}

.auth-switch button:hover {
  text-decoration: underline;
}

.auth-form :deep(.el-form-item) {
  margin-bottom: 20px;
}

.auth-form :deep(.el-form-item__label) {
  padding-bottom: 7px;
  font-size: 13px;
  line-height: 1.4;
  color: var(--text-secondary);
}

.auth-form :deep(.el-input__wrapper) {
  padding: 5px 14px;
  border-radius: 10px;
  box-shadow: 0 0 0 1px var(--border) inset;
  transition: box-shadow var(--transition);
}

.auth-form :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--text-muted) inset;
}

.auth-form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1.5px var(--primary) inset;
}

.submit {
  width: 100%;
  --el-button-size: 46px;
  margin-top: 6px;
  border-radius: 10px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.1em;
}

/* 窄屏：左栏让位给表单，顶部补一行品牌 */
@media (max-width: 880px) {
  .hero {
    display: none;
  }

  .auth-brand {
    display: flex;
  }
}
</style>
