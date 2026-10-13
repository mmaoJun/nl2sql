<template>
  <!-- 宽度动画收起：两层内容（窄栏 rail / 全量 full）叠放，靠透明度切换，
       容器 width 过渡承担动画，避免 v-if 直接换 DOM 的生硬跳变 -->
  <div :class="['session-list', { collapsed }]">
    <div class="rail">
      <el-tooltip content="展开侧栏" placement="right" :show-after="80">
        <button class="rail-btn" aria-label="展开侧栏" @click="$emit('update:collapsed', false)">
          <el-icon :size="17"><Expand /></el-icon>
        </button>
      </el-tooltip>
      <el-tooltip content="新对话" placement="right" :show-after="80">
        <button class="rail-btn" aria-label="新对话" @click="$emit('create')">
          <el-icon :size="17"><Plus /></el-icon>
        </button>
      </el-tooltip>
      <el-tooltip content="数据源管理" placement="right" :show-after="80">
        <button class="rail-btn" aria-label="数据源管理" @click="goDatasources">
          <el-icon :size="17"><Coin /></el-icon>
        </button>
      </el-tooltip>
      <div class="rail-spacer"></div>
      <el-tooltip :content="username || '未登录'" placement="right" :show-after="80">
        <button class="rail-avatar" aria-label="账号" @click="$emit('update:collapsed', false)">
          {{ username.charAt(0).toUpperCase() || '?' }}
        </button>
      </el-tooltip>
    </div>

    <div class="full">
      <div class="sidebar-header">
        <div class="brand">
          <span class="brand-logo">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>
          </span>
          <span class="brand-name">NL2SQL</span>
        </div>
        <button class="header-btn" title="收起侧栏" @click="$emit('update:collapsed', true)">
          <el-icon :size="16"><Fold /></el-icon>
        </button>
      </div>

      <button class="sidebar-nav-btn" @click="$emit('create')">
        <el-icon :size="15"><Plus /></el-icon>
        <span>新对话</span>
      </button>

      <button class="sidebar-nav-btn" @click="goDatasources">
        <el-icon :size="15"><Coin /></el-icon>
        <span>数据源管理</span>
      </button>

      <div class="session-section-title">最近对话</div>

      <div class="session-items" v-loading="loading">
        <div v-if="!loading && sessions.length === 0" class="session-empty">
          还没有对话
        </div>

        <div
          v-for="s in sessions"
          :key="s.id"
          :class="['session-item', { active: s.id === currentId }]"
          @click="$emit('select', s.id)"
        >
          <div class="session-main">
            <div class="session-title-row">
              <span class="session-title">{{ s.title || '新对话' }}</span>
              <span class="session-meta">{{ metaText(s) }}</span>
            </div>
            <span v-if="s.preview" class="session-preview">{{ s.preview }}</span>
          </div>
          <div class="session-actions" @click.stop>
            <button class="action-btn" title="重命名" @click="onRename(s)">
              <el-icon :size="13"><EditPen /></el-icon>
            </button>
            <button class="action-btn danger" title="删除" @click="onRemove(s)">
              <el-icon :size="13"><Delete /></el-icon>
            </button>
          </div>
        </div>
      </div>

      <div class="session-footer">
        <div class="user-avatar">{{ username.charAt(0).toUpperCase() || '?' }}</div>
        <span class="user-name">{{ username || '未登录' }}</span>
        <button class="logout-btn" title="退出登录" @click="logout">
          <el-icon :size="13"><SwitchButton /></el-icon>
          <span>退出</span>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Plus, EditPen, Delete, SwitchButton, Expand, Fold, Coin } from '@element-plus/icons-vue'

const router = useRouter()

defineProps({
  sessions: { type: Array, default: () => [] },
  currentId: { type: Number, default: null },
  loading: { type: Boolean, default: false },
  collapsed: { type: Boolean, default: false }
})

const emit = defineEmits(['create', 'select', 'rename', 'remove', 'update:collapsed'])

const username = ref('')
watch(() => router.currentRoute.value.path, () => {
  username.value = localStorage.getItem('username') || ''
}, { immediate: true })

function goDatasources() {
  router.push('/datasources')
}

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  router.push('/login')
}

// 后端的时间字段有两种形态：单条返回是内存对象的 "2026-10-08T17:44:12.3145848"，
// 列表返回是库里读出的 "2026-10-08T17:44:12"（Jackson 的 date-format 只作用于
// java.util.Date，LocalDateTime 走 ISO-8601）。去掉 T 与小数秒再把横杠换斜杠，
// 两种都能吃下，也避开部分浏览器对带横杠日期串解析不稳定的问题。
function formatTime(value) {
  if (!value) return ''
  const normalized = String(value).split('.')[0].replace('T', ' ').replace(/-/g, '/')
  const date = new Date(normalized)
  if (Number.isNaN(date.getTime())) return ''
  const now = new Date()
  if (date.toDateString() === now.toDateString()) {
    return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  }
  return `${date.getMonth() + 1}/${date.getDate()}`
}

// 时间与轮数合并成标题行右侧的一枚小灰字，保证会话行紧凑（改为两行结构后
// 行高约 36–48px）；两段信息都保留，只是排版位置调整
function metaText(session) {
  const time = formatTime(session.updatedAt)
  const turns = `${session.turnCount || 0} 轮`
  return time ? `${time} · ${turns}` : turns
}

async function onRename(session) {
  try {
    const { value } = await ElMessageBox.prompt('给这场对话起个名字', '重命名会话', {
      inputValue: session.title || '',
      inputValidator: (v) => (v && v.trim() ? true : '标题不能为空'),
      confirmButtonText: '保存',
      cancelButtonText: '取消'
    })
    emit('rename', session.id, value.trim())
  } catch {
    // 用户点了取消，什么都不做
  }
}

async function onRemove(session) {
  try {
    await ElMessageBox.confirm(
      `删除「${session.title || '新对话'}」？该对话的 ${session.turnCount || 0} 轮记录会一并删除。`,
      '删除会话',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
    emit('remove', session.id)
  } catch {
    // 用户点了取消，什么都不做
  }
}
</script>

<style scoped>
.session-list {
  position: relative;
  width: 260px;
  flex-shrink: 0;
  border-right: 1px solid var(--border-light);
  background: var(--surface-hover);
  min-height: 0;
  overflow: hidden;
  transition: width 0.25s cubic-bezier(0.4, 0, 0.2, 1);
}

.session-list.collapsed {
  width: 56px;
}

/* ---- 窄栏：绝对定位叠在全量层之上，靠透明度过渡进出 ---- */
.rail {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 56px;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 10px 0;
  gap: 6px;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease;
}

.collapsed .rail {
  opacity: 1;
  pointer-events: auto;
  transition: opacity 0.2s ease 0.1s;
}

.rail-spacer {
  flex: 1;
}

.rail-btn {
  width: 38px;
  height: 38px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all var(--transition);
}

.rail-btn:hover {
  background: var(--bg);
  color: var(--text);
}

.rail-avatar {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 50%;
  background: var(--primary);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all var(--transition);
}

.rail-avatar:hover {
  background: var(--primary-dark);
}

/* ---- 全量层：固定 260 宽，收起时淡出（宽度动画期间内容不挤压变形） ---- */
.full {
  width: 260px;
  height: 100%;
  display: flex;
  flex-direction: column;
  opacity: 1;
  transition: opacity 0.15s ease;
}

.collapsed .full {
  opacity: 0;
  pointer-events: none;
}

.sidebar-header {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 14px 12px 10px 14px;
  flex-shrink: 0;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.brand-logo {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: var(--primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.brand-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--text);
  letter-spacing: 0.01em;
}

.header-btn {
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  padding: 5px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  transition: all var(--transition);
}

.header-btn:hover {
  background: var(--bg);
  color: var(--text);
}

/* ---- 中部：新对话 + 最近对话 ---- */
.sidebar-nav-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 4px 8px 0;
  padding: 0 10px;
  height: 36px;
  flex-shrink: 0;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all var(--transition);
}

.sidebar-nav-btn:hover {
  background: var(--bg);
  color: var(--text);
}

.session-section-title {
  padding: 14px 18px 6px;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-muted);
  letter-spacing: 0.02em;
  user-select: none;
  flex-shrink: 0;
}

.session-items {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px 10px;
  min-height: 120px;
}

.session-empty {
  padding: 24px 12px;
  text-align: center;
  font-size: 12.5px;
  color: var(--text-muted);
}

.session-item {
  position: relative;
  padding: 6px 10px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 2px;
  min-height: 36px;
  transition: background var(--transition);
}

.session-item:hover {
  background: var(--bg);
}

.session-item.active,
.session-item.active:hover {
  background: var(--bg);
}

.session-item.active .session-title {
  color: var(--text);
  font-weight: 600;
}

/* 悬浮操作占住右上角，此时把同位置的 meta 小灰字藏掉避免叠字 */
.session-item:hover .session-meta {
  visibility: hidden;
}

.session-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.session-title-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.session-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.35;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-meta {
  flex-shrink: 0;
  font-size: 11px;
  line-height: 1.35;
  color: var(--text-muted);
  white-space: nowrap;
}

.session-preview {
  font-size: 11.5px;
  line-height: 1.35;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-actions {
  position: absolute;
  right: 6px;
  top: 6px;
  display: none;
  gap: 2px;
}

.session-item:hover .session-actions {
  display: flex;
}

.action-btn {
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  padding: 3px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  transition: all var(--transition);
}

.action-btn:hover {
  background: var(--border);
  color: var(--text);
}

.action-btn.danger:hover {
  background: var(--error-bg);
  color: var(--error);
}

/* ---- 底部：登录信息 + 退出 ---- */
.session-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid var(--border-light);
  flex-shrink: 0;
}

.user-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

.user-name {
  flex: 1;
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.logout-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--surface);
  color: var(--text-secondary);
  font-size: 12.5px;
  cursor: pointer;
  transition: all var(--transition);
  flex-shrink: 0;
}

.logout-btn:hover {
  color: var(--error);
  border-color: var(--error);
  background: var(--error-bg);
}

/* 键盘可达性：按钮保持一致的 focus 视觉 */
.sidebar-nav-btn:focus-visible,
.header-btn:focus-visible,
.rail-btn:focus-visible,
.rail-avatar:focus-visible,
.logout-btn:focus-visible {
  outline: 2px solid var(--primary-light);
  outline-offset: 1px;
}
</style>
