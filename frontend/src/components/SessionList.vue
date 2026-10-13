<template>
  <div class="session-list">
    <!-- 顶部：数据源管理入口（数据源选择已移到对话输入框下方，由 ChatPage 承载） -->
    <div class="sidebar-top">
      <button class="ds-manage-btn" @click="goSettings">
        <el-icon :size="15"><Coin /></el-icon>
        <span>数据源管理</span>
      </button>
    </div>

    <button class="new-session-btn" @click="$emit('create')">
      <el-icon :size="15"><Plus /></el-icon>
      <span>新对话</span>
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
      <button class="footer-btn" title="设置" @click="goSettings">
        <el-icon :size="16"><Setting /></el-icon>
      </button>
      <button class="logout-btn" title="退出登录" @click="logout">
        <el-icon :size="13"><SwitchButton /></el-icon>
        <span>退出</span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Plus, EditPen, Delete, Setting, SwitchButton, Coin } from '@element-plus/icons-vue'

const router = useRouter()

defineProps({
  sessions: { type: Array, default: () => [] },
  currentId: { type: Number, default: null },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['create', 'select', 'rename', 'remove'])

const username = ref('')
watch(() => router.currentRoute.value.path, () => {
  username.value = localStorage.getItem('username') || ''
}, { immediate: true })

function goSettings() {
  router.push('/settings')
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
  width: 260px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--border-light);
  background: var(--surface-hover);
  min-height: 0;
}

/* ---- 顶部：数据源管理入口（全宽） ---- */
.sidebar-top {
  padding: 12px 12px 2px;
  flex-shrink: 0;
}

.ds-manage-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 0 12px;
  height: 36px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface);
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all var(--transition);
}

.ds-manage-btn:hover {
  border-color: var(--primary-light);
  background: var(--primary-bg);
  color: var(--primary);
}

/* ---- 中部：新对话 + 最近对话 ---- */
.new-session-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 6px 8px 0;
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

.new-session-btn:hover {
  background: var(--primary-bg);
  color: var(--primary);
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
  background: var(--primary-bg);
}

.session-item.active .session-title {
  color: var(--primary);
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

/* ---- 底部：登录信息 + 设置 / 退出 ---- */
.session-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid var(--border);
  flex-shrink: 0;
}

.user-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--primary), var(--primary-light));
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
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

.footer-btn {
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  padding: 5px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all var(--transition);
  flex-shrink: 0;
}

.footer-btn:hover {
  background: var(--bg);
  color: var(--text);
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

/* 键盘可达性：新的按钮保持一致的 focus 视觉 */
.new-session-btn:focus-visible,
.ds-manage-btn:focus-visible,
.footer-btn:focus-visible,
.logout-btn:focus-visible {
  outline: 2px solid var(--primary-light);
  outline-offset: 1px;
}
</style>
