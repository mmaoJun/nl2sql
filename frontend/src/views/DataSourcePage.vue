<template>
  <div class="ds-page">
    <div class="page-header">
      <div class="header-left">
        <button class="back-btn" @click="router.push('/chat')">
          <el-icon :size="16"><ArrowLeft /></el-icon>
          <span>返回对话</span>
        </button>
        <div>
          <h2 class="page-title">数据源管理</h2>
          <p class="page-desc">按引擎类型配置连接，每种类型一个连接即可在对话中选用</p>
        </div>
      </div>
    </div>

    <div class="type-grid">
      <div v-for="t in typeCards" :key="t.code" class="type-card" :class="{ configured: t.records.length > 0 }">
        <div class="card-head">
          <span class="type-icon" :style="{ background: typeColor(t.code) }">
            {{ t.displayName.charAt(0) }}
          </span>
          <div class="card-title">
            <span class="card-name">{{ t.displayName }}</span>
            <span class="card-code">{{ t.code }}</span>
          </div>
          <span class="card-status" :class="{ on: t.records.length > 0 }">
            {{ t.records.length > 0 ? '已配置' : '未配置' }}
          </span>
        </div>

        <div v-if="t.records.length === 0" class="card-empty">
          <span>还没有配置该类型的连接</span>
          <button class="btn-primary sm" @click="openCreate(t)">配置连接</button>
        </div>

        <div v-else class="card-records">
          <div v-for="r in t.records" :key="r.id" class="record-row">
            <div class="record-info">
              <span class="record-target">{{ r.host }}:{{ r.port }}</span>
              <span v-if="r.databaseName" class="record-db">{{ r.databaseName }}</span>
            </div>
            <div class="record-actions">
              <button class="icon-btn" title="测试连接" :disabled="testingId === r.id" @click="handleTest(r.id)">
                <el-icon :size="14"><Loading v-if="testingId === r.id" /><Connection v-else /></el-icon>
              </button>
              <button class="icon-btn" title="编辑" @click="openEdit(t, r)">
                <el-icon :size="14"><EditPen /></el-icon>
              </button>
              <button class="icon-btn danger" title="删除" @click="handleDelete(r)">
                <el-icon :size="14"><Delete /></el-icon>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="showDialog" class="dialog-overlay" @click.self="closeDialog">
        <div class="dialog-card">
          <div class="dialog-header">
            <h3>{{ editingId ? `编辑 ${dialogType.displayName} 连接` : `配置 ${dialogType.displayName} 连接` }}</h3>
            <button class="dialog-close" @click="closeDialog">
              <el-icon :size="18"><Close /></el-icon>
            </button>
          </div>
          <div class="dialog-body">
            <div class="form-row">
              <div class="form-group flex-2">
                <label class="form-label">主机</label>
                <input v-model="form.host" class="form-input" placeholder="localhost" />
              </div>
              <div class="form-group flex-1">
                <label class="form-label">端口</label>
                <input v-model.number="form.port" type="number" class="form-input" :placeholder="String(dialogType.defaultPort || '')" min="1" max="65535" />
              </div>
            </div>
            <div class="form-group">
              <label class="form-label">{{ isApiType ? '基础路径' : '数据库名' }}</label>
              <input v-model="form.databaseName" class="form-input" :placeholder="isApiType ? '/api' : 'my_database'" />
            </div>
            <div class="form-row">
              <div class="form-group flex-1">
                <label class="form-label">用户名</label>
                <input v-model="form.username" class="form-input" :placeholder="defaultUsername" />
              </div>
              <div class="form-group flex-1">
                <label class="form-label">密码</label>
                <input v-model="form.password" type="password" class="form-input" :placeholder="editingId ? '留空表示不修改' : '密码'" />
              </div>
            </div>
            <div v-if="isApiType" class="form-group">
              <label class="form-label">高级配置（JSON：headers / tables）</label>
              <textarea
                v-model="form.extraConfig"
                class="form-input form-textarea"
                rows="5"
                placeholder='{"headers":{"X-API-KEY":"..."},"tables":[{"name":"department_budgets","path":"/departments"}]}'
              ></textarea>
              <span v-if="extraConfigError" class="field-error">JSON 格式不正确</span>
            </div>
          </div>
          <div class="dialog-footer">
            <button class="btn-ghost" @click="closeDialog">取消</button>
            <button class="btn-primary" @click="handleSubmit" :disabled="creating || extraConfigError">
              <span v-if="creating">保存中...</span>
              <span v-else>{{ editingId ? '保存' : '创建' }}</span>
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, EditPen, Delete, Connection, Loading, Close } from '@element-plus/icons-vue'
import { useDataSourceStore } from '../stores/datasourceStore'
import { createDataSource, updateDataSource, deleteDataSource, testDataSource } from '../api/endpoints'

const router = useRouter()
const dsStore = useDataSourceStore()

const showDialog = ref(false)
const creating = ref(false)
const testingId = ref(null)
/** 当前编辑的数据源 id；为空表示新增模式（新增/编辑共用同一个对话框） */
const editingId = ref(null)
/** 对话框绑定的引擎类型（类型制：由卡片决定，表单内不再切换类型） */
const dialogType = ref({ code: 'mysql', displayName: 'MySQL', defaultPort: 3306 })

const form = reactive({
  name: '',
  host: 'localhost',
  port: 3306,
  databaseName: '',
  username: '',
  password: '',
  extraConfig: ''
})

// 类型图标底色走灰阶梯度（黑白灰主题），仅靠深浅区分类型
const TYPE_COLORS = {
  mysql: '#26282c',
  postgresql: '#3f4247',
  hive: '#55585c',
  hdfs: '#6b6e72',
  rest: '#838689',
  graphql: '#9ca0a3'
}

function typeColor(code) {
  return TYPE_COLORS[code] || '#64748b'
}

// supported-types 拉取失败时回退：已有数据源类型 + MySQL，保证页面可编辑
const supportedTypes = computed(() =>
  dsStore.supportedTypes.length
    ? dsStore.supportedTypes
    : [...new Set(dsStore.dataSources.map((d) => d.type)), 'mysql']
        .filter((v, i, arr) => arr.indexOf(v) === i)
        .map((code) => ({ code, displayName: code.toUpperCase(), defaultPort: null }))
)

const typeCards = computed(() =>
  supportedTypes.value.map((t) => ({
    ...t,
    records: dsStore.dataSources.filter((d) => d.type === t.code)
  }))
)

const isApiType = computed(() => dialogType.value.code === 'rest' || dialogType.value.code === 'graphql')

const DEFAULT_USERNAMES = { mysql: 'root', postgresql: 'postgres' }
const defaultUsername = computed(() => DEFAULT_USERNAMES[dialogType.value.code] || '')

const extraConfigError = computed(() => {
  if (!isApiType.value || !form.extraConfig.trim()) return false
  try {
    JSON.parse(form.extraConfig)
    return false
  } catch {
    return true
  }
})

function openCreate(t) {
  editingId.value = null
  dialogType.value = t
  Object.assign(form, {
    // 类型制命名：新建连接名固定为「{类型}数据源」，用户不再给实例起名
    name: `${t.displayName}数据源`,
    host: 'localhost',
    port: t.defaultPort || 3306,
    databaseName: isApiTypeCode(t.code) ? '/api' : '',
    username: DEFAULT_USERNAMES[t.code] || '',
    password: '',
    extraConfig: ''
  })
  showDialog.value = true
}

function isApiTypeCode(code) {
  return code === 'rest' || code === 'graphql'
}

function openEdit(t, row) {
  editingId.value = row.id
  dialogType.value = t
  Object.assign(form, {
    // 编辑保留原连接名（含历史数据里的演示名），不按类型重命名
    name: row.name,
    host: row.host,
    port: row.port,
    databaseName: row.databaseName || '',
    username: row.username || '',
    // 后端列表密码已脱敏为 "******"；留空提交时按"不修改"处理（服务层同语义）
    password: '',
    extraConfig: row.extraConfig || ''
  })
  showDialog.value = true
}

function closeDialog() {
  showDialog.value = false
  editingId.value = null
}

function buildPayload() {
  return {
    name: form.name,
    type: dialogType.value.code,
    host: form.host,
    port: form.port,
    databaseName: form.databaseName,
    username: form.username,
    password: form.password || '******',
    extraConfig: isApiType.value ? form.extraConfig.trim() : null
  }
}

async function handleSubmit() {
  if (!form.host.trim()) {
    ElMessage.warning('请填写主机')
    return
  }
  creating.value = true
  try {
    const payload = buildPayload()
    if (editingId.value) {
      await updateDataSource(editingId.value, payload)
      ElMessage.success('连接已更新')
    } else {
      await createDataSource(payload)
      ElMessage.success('连接已创建')
    }
    closeDialog()
    await dsStore.fetchDataSources()
  } catch (e) {
    // error handled by interceptor
  } finally {
    creating.value = false
  }
}

async function handleTest(id) {
  testingId.value = id
  try {
    const res = await testDataSource(id)
    if (res.data) {
      ElMessage.success('连接成功')
    } else {
      ElMessage.error('连接失败')
    }
  } catch (e) {
    // 连接失败时后端返回业务码 500 + "连接失败"，提示已由拦截器弹出
  } finally {
    testingId.value = null
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `删除 ${row.host}:${row.port} 的连接？对话中将无法再选用该类型数据源。`,
      '删除连接',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return // 用户取消
  }
  try {
    await deleteDataSource(row.id)
    ElMessage.success('删除成功')
    await dsStore.fetchDataSources()
  } catch (e) {
    // error handled by interceptor
  }
}

onMounted(() => {
  dsStore.fetchDataSources()
  dsStore.fetchSupportedTypes()
})
</script>

<style scoped>
.ds-page {
  padding: 32px;
  height: 100%;
  overflow-y: auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 28px;
}

.header-left {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  align-self: flex-start;
  padding: 6px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all var(--transition);
}

.back-btn:hover {
  color: var(--primary);
  border-color: var(--primary-light);
  background: var(--primary-bg);
}

.page-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--text);
  margin-bottom: 4px;
}

.page-desc {
  font-size: 14px;
  color: var(--text-muted);
}

/* ---- 类型卡片 ---- */
.type-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}

.type-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 18px;
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
  gap: 14px;
  transition: all var(--transition);
}

.type-card.configured {
  border-color: var(--border);
}

.type-card:hover {
  box-shadow: var(--shadow);
}

.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.type-icon {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 700;
  flex-shrink: 0;
}

.card-title {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.card-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
}

.card-code {
  font-size: 11px;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.3px;
}

.card-status {
  font-size: 11.5px;
  padding: 2px 8px;
  border-radius: 10px;
  background: var(--bg);
  color: var(--text-muted);
  flex-shrink: 0;
}

.card-status.on {
  background: var(--success-bg);
  color: var(--success);
}

.card-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 10px 0 4px;
  font-size: 12.5px;
  color: var(--text-muted);
}

.card-records {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.record-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius);
  background: var(--surface-hover);
}

.record-info {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.record-target {
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
  white-space: nowrap;
}

.record-db {
  font-size: 12px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.record-actions {
  display: flex;
  gap: 2px;
  flex-shrink: 0;
}

.icon-btn {
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

.icon-btn:hover:not(:disabled) {
  background: var(--primary-bg);
  color: var(--primary);
}

.icon-btn.danger:hover {
  background: var(--error-bg);
  color: var(--error);
}

.icon-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ---- 按钮 ---- */
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 20px;
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: var(--radius);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--transition);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.2);
}

.btn-primary.sm {
  padding: 7px 14px;
  font-size: 13px;
}

.btn-primary:hover:not(:disabled) {
  background: var(--primary-dark);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.28);
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-ghost {
  padding: 10px 20px;
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  font-size: 14px;
  cursor: pointer;
  transition: all var(--transition);
}

.btn-ghost:hover {
  background: var(--bg);
  border-color: #cbd5e1;
}

/* ---- Dialog（沿用原设置页样式） ---- */
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.5);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  animation: fadeIn 0.2s ease;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.dialog-card {
  background: var(--surface);
  border-radius: var(--radius-xl);
  width: 520px;
  max-width: 90vw;
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.25);
  animation: slideUp 0.25s ease;
}

@keyframes slideUp {
  from { opacity: 0; transform: translateY(20px); }
  to { opacity: 1; transform: translateY(0); }
}

.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid var(--border);
}

.dialog-header h3 {
  font-size: 17px;
  font-weight: 600;
  color: var(--text);
}

.dialog-close {
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  border-radius: 6px;
  color: var(--text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all var(--transition);
}

.dialog-close:hover {
  background: var(--bg);
  color: var(--text);
}

.dialog-body {
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-row {
  display: flex;
  gap: 16px;
}

.flex-1 { flex: 1; }
.flex-2 { flex: 2; }

.form-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
}

.form-input {
  padding: 10px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  font-size: 14px;
  font-family: var(--font-sans);
  color: var(--text);
  transition: all var(--transition);
  outline: none;
  background: var(--surface);
}

.form-textarea {
  resize: vertical;
  font-family: var(--font-mono, monospace);
  font-size: 12.5px;
  line-height: 1.5;
}

.form-input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(0, 0, 0, 0.06);
}

.form-input::placeholder {
  color: var(--text-muted);
}

.field-error {
  font-size: 12px;
  color: var(--error);
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 24px;
  border-top: 1px solid var(--border);
}
</style>
