<template>
  <div class="session-list">
    <button class="new-session-btn" @click="$emit('create')">
      <el-icon :size="15"><Plus /></el-icon>
      <span>新建对话</span>
    </button>

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
          <span class="session-title">{{ s.title || '新对话' }}</span>
          <span v-if="s.preview" class="session-preview">{{ s.preview }}</span>
        </div>
        <div class="session-meta">
          <span>{{ formatTime(s.updatedAt) }}</span>
          <span>{{ s.turnCount || 0 }} 轮</span>
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
      <el-popover placement="top-start" :width="160" trigger="click">
        <template #reference>
          <button class="settings-btn" title="设置">
            <el-icon :size="16"><Setting /></el-icon>
          </button>
        </template>
        <div class="popover-menu">
          <button class="popover-item" @click="goSettings">
            <el-icon :size="15"><Setting /></el-icon>
            <span>数据源管理</span>
          </button>
          <button class="popover-item danger" @click="logout">
            <el-icon :size="15"><SwitchButton /></el-icon>
            <span>退出登录</span>
          </button>
        </div>
      </el-popover>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Plus, EditPen, Delete, Setting, SwitchButton } from '@element-plus/icons-vue'

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
  background: var(--surface);
  min-height: 0;
}

.new-session-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin: 12px;
  padding: 9px 12px;
  border: 1px dashed var(--border);
  border-radius: var(--radius);
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all var(--transition);
}

.new-session-btn:hover {
  border-color: var(--primary-light);
  color: var(--primary);
  background: var(--primary-bg);
}

.session-items {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px 12px;
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
  padding: 9px 10px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 2px;
  transition: background var(--transition);
}

.session-item:hover {
  background: var(--surface-hover);
}

.session-item.active {
  background: var(--primary-bg);
}

.session-item.active .session-title {
  color: var(--primary);
}

.session-main {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.session-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-preview {
  font-size: 11.5px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-meta {
  display: flex;
  gap: 8px;
  margin-top: 4px;
  font-size: 11px;
  color: var(--text-muted);
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
}

.action-btn:hover {
  background: var(--border-light);
  color: var(--text);
}

.action-btn.danger:hover {
  background: var(--error-bg);
  color: var(--error);
}

.session-footer {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-top: 1px solid var(--border-light);
}

.user-avatar {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: linear-gradient(135deg, #4f46e5, #7c3aed);
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

.settings-btn {
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  transition: all 0.15s ease;
}

.settings-btn:hover {
  background: var(--surface-hover);
  color: var(--text);
}

.popover-menu {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.popover-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border: none;
  background: transparent;
  color: var(--text);
  font-size: 13px;
  cursor: pointer;
  border-radius: 6px;
  transition: all 0.15s ease;
  width: 100%;
  text-align: left;
}

.popover-item:hover {
  background: var(--surface-hover);
}

.popover-item.danger:hover {
  background: var(--error-bg);
  color: var(--error);
}
</style>
