<template>
  <div class="ds-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">数据源</h2>
        <p class="page-desc">在这里查看和管理您的数据源</p>
      </div>
      <el-dropdown trigger="click" @command="openCreateByCode">
        <button class="btn-primary">
          <el-icon :size="14"><Plus /></el-icon>
          <span>添加数据源</span>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-for="t in typeOptions" :key="t.code" :command="t.code">
              {{ t.displayName }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <div class="filter-row">
      <div class="filter-input">
        <el-icon :size="15"><Search /></el-icon>
        <input v-model="keyword" class="filter-ipt" placeholder="按名称过滤..." />
      </div>
      <span class="filter-count">共 {{ dataSources.length }} 个数据源</span>
    </div>

    <div v-if="filtered.length" class="ds-grid">
      <div v-for="ds in filtered" :key="ds.id" class="ds-card">
        <div class="card-head">
          <span class="type-icon" :style="{ background: typeColor(ds.type) }">
            {{ (ds.type || '?').charAt(0).toUpperCase() }}
          </span>
          <span class="card-name">{{ ds.name }}</span>
          <span class="type-badge">{{ (ds.type || '').toUpperCase() }}</span>
          <el-tooltip v-if="testedOk.has(ds.id)" content="最近一次测试连接成功" placement="top" :show-after="80">
            <el-icon :size="16" class="card-check"><CircleCheck /></el-icon>
          </el-tooltip>
        </div>

        <p class="card-desc">{{ describe(ds) }}</p>

        <div class="card-footer">
          <span class="card-meta">{{ relativeTime(ds.updatedAt) }}</span>
          <div class="card-actions">
            <button class="icon-btn" title="测试连接" :disabled="testingId === ds.id" @click="handleTest(ds.id)">
              <el-icon :size="14"><Loading v-if="testingId === ds.id" /><Connection v-else /></el-icon>
            </button>
            <button class="icon-btn" title="编辑" @click="openEdit(ds)">
              <el-icon :size="14"><EditPen /></el-icon>
            </button>
            <button class="icon-btn danger" title="删除" @click="handleDelete(ds)">
              <el-icon :size="14"><Delete /></el-icon>
            </button>
          </div>
        </div>
      </div>
    </div>

    <div v-else class="ds-empty">
      <p class="empty-text">
        {{ keyword.trim() ? '没有匹配的数据源' : '还没有数据源，先添加一个吧' }}
      </p>
      <button v-if="!keyword.trim()" class="btn-primary sm" @click="openCreateByCode(typeOptions[0].code)">
        <el-icon :size="14"><Plus /></el-icon>
        <span>添加数据源</span>
      </button>
    </div>

    <Teleport to="body">
      <div v-if="showDialog" class="dialog-overlay" @click.self="closeDialog">
        <div class="dialog-card">
          <div class="dialog-header">
            <h3>{{ editingId ? `编辑 ${dialogType.displayName} 数据源` : `添加 ${dialogType.displayName} 数据源` }}</h3>
            <button class="dialog-close" @click="closeDialog">
              <el-icon :size="18"><Close /></el-icon>
            </button>
          </div>
          <div class="dialog-body">
            <div class="form-group">
              <label class="form-label">名称</label>
              <input v-model="form.name" class="form-input" placeholder="数据源名称" />
            </div>
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
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Plus, Search, EditPen, Delete, Connection, Loading, Close, CircleCheck
} from '@element-plus/icons-vue'
import { useDataSourceStore } from '../stores/datasourceStore'
import { createDataSource, updateDataSource, deleteDataSource, testDataSource } from '../api/endpoints'

const dsStore = useDataSourceStore()

const showDialog = ref(false)
const creating = ref(false)
const testingId = ref(null)
const keyword = ref('')
/** 最近一次测试连接成功的 id（仅页面内存态，不落库、刷新即清） */
const testedOk = ref(new Set())
/** 当前编辑的数据源 id；为空表示新增模式（新增/编辑共用一个对话框） */
const editingId = ref(null)
/** 对话框绑定的引擎类型（由「添加数据源」下拉决定，表单内不切换类型） */
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

// supported-types 拉取失败时的兜底清单（与后端注册的六类一致）
const FALLBACK_TYPES = [
  { code: 'mysql', displayName: 'MySQL', defaultPort: 3306 },
  { code: 'postgresql', displayName: 'PostgreSQL', defaultPort: 5432 },
  { code: 'hive', displayName: 'Hive', defaultPort: 10000 },
  { code: 'hdfs', displayName: 'HDFS', defaultPort: 9870 },
  { code: 'rest', displayName: 'REST API', defaultPort: 80 },
  { code: 'graphql', displayName: 'GraphQL', defaultPort: 80 }
]

const typeOptions = computed(() => (dsStore.supportedTypes.length ? dsStore.supportedTypes : FALLBACK_TYPES))
const dataSources = computed(() => dsStore.dataSources)

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return dataSources.value
  return dataSources.value.filter((ds) => `${ds.name || ''} ${ds.type || ''}`.toLowerCase().includes(kw))
})

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

function typeColor(code) {
  return TYPE_COLORS[code] || '#64748b'
}

/** 卡片副文案：当前没有采集库/表统计，用连接目标替代"描述" */
function describe(ds) {
  const target = `${ds.host || '-'}:${ds.port || '-'}`
  return ds.databaseName ? `${target} · ${ds.databaseName}` : target
}

function isApiTypeCode(code) {
  return code === 'rest' || code === 'graphql'
}

// 后端时间字段两种形态（单条带小数秒 / 列表 ISO-8601），清洗后统一算相对时间
function relativeTime(value) {
  if (!value) return ''
  const normalized = String(value).split('.')[0].replace('T', ' ').replace(/-/g, '/')
  const date = new Date(normalized)
  if (Number.isNaN(date.getTime())) return ''
  const minutes = Math.floor((Date.now() - date.getTime()) / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `大约 ${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `大约 ${hours} 小时前`
  return `大约 ${Math.floor(hours / 24)} 天前`
}

function openCreateByCode(code) {
  const t = typeOptions.value.find((item) => item.code === code) || FALLBACK_TYPES[0]
  editingId.value = null
  dialogType.value = t
  Object.assign(form, {
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

function openEdit(row) {
  editingId.value = row.id
  dialogType.value = typeOptions.value.find((t) => t.code === row.type)
    || { code: row.type, displayName: (row.type || '').toUpperCase(), defaultPort: row.port }
  Object.assign(form, {
    // 编辑保留原名称（含历史数据里的演示名）
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
  if (!form.name.trim() || !form.host.trim()) {
    ElMessage.warning('请填写名称与主机')
    return
  }
  creating.value = true
  try {
    const payload = buildPayload()
    if (editingId.value) {
      await updateDataSource(editingId.value, payload)
      ElMessage.success('数据源已更新')
    } else {
      await createDataSource(payload)
      ElMessage.success('数据源创建成功')
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
      testedOk.value = new Set(testedOk.value).add(id)
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
      `删除「${row.name}」？对话中将无法再选用该数据源。`,
      '删除数据源',
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
  padding: 28px 32px 40px;
  height: 100%;
  overflow-y: auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 18px;
}

.page-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--text);
  margin-bottom: 4px;
}

.page-desc {
  font-size: 13px;
  color: var(--text-muted);
}

/* ---- 过滤 ---- */
.filter-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.filter-input {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 320px;
  max-width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--text-muted);
  transition: box-shadow var(--transition);
}

.filter-input:focus-within {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.06);
}

.filter-ipt {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--text);
}

.filter-ipt::placeholder {
  color: var(--text-muted);
}

.filter-count {
  font-size: 12.5px;
  color: var(--text-muted);
}

/* ---- 卡片 ---- */
.ds-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 14px;
  align-items: start;
}

.ds-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--surface);
  padding: 16px 18px 12px;
  transition: all var(--transition);
}

.ds-card:hover {
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.07);
}

.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.type-icon {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  font-weight: 700;
  flex-shrink: 0;
}

.card-name {
  flex: 1;
  min-width: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.type-badge {
  flex-shrink: 0;
  padding: 1px 8px;
  border-radius: 6px;
  background: var(--bg);
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.3px;
}

.card-check {
  color: var(--primary);
  flex-shrink: 0;
}

.card-desc {
  margin: 10px 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary);
  min-height: 21px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-top: 10px;
  border-top: 1px solid var(--border-light);
}

.card-meta {
  font-size: 12.5px;
  color: var(--text-muted);
}

.card-actions {
  display: flex;
  gap: 2px;
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

/* ---- 空态 ---- */
.ds-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding: 80px 0;
}

.empty-text {
  font-size: 13px;
  color: var(--text-muted);
}

/* ---- 按钮 ---- */
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 16px;
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: var(--radius);
  font-size: 13.5px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--transition);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.16);
}

.btn-primary.sm {
  padding: 7px 14px;
  font-size: 13px;
}

.btn-primary:hover:not(:disabled) {
  background: var(--primary-dark);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.22);
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

/* ---- Dialog ---- */
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
