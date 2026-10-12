<template>
  <div class="settings-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">数据源管理</h2>
        <p class="page-desc">配置和管理数据库连接，用于 SQL 查询</p>
      </div>
      <button class="btn-primary" @click="showDialog = true">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
        新增数据源
      </button>
    </div>

    <div class="table-card">
      <el-table
        :data="dsStore.dataSources"
        style="width: 100%"
        :header-cell-style="headerStyle"
      >
        <el-table-column prop="name" label="名称" min-width="150">
          <template #default="{ row }">
            <div class="cell-name">
              <div class="name-icon">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>
              </div>
              <span>{{ row.name }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="100">
          <template #default="{ row }">
            <span class="type-badge">{{ row.type }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="host" label="主机" min-width="140" />
        <el-table-column prop="port" label="端口" width="80" />
        <el-table-column prop="databaseName" label="数据库" min-width="130" />
        <el-table-column label="操作" width="200" fixed="right" align="center">
          <template #default="{ row }">
            <button class="action-btn test-btn" @click="handleTest(row.id)" :disabled="testingId === row.id">
              <svg v-if="testingId !== row.id" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>
              <span v-if="testingId === row.id">测试中...</span>
              <span v-else>测试</span>
            </button>
            <button class="action-btn delete-btn" @click="handleDelete(row.id)">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
              删除
            </button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <Teleport to="body">
      <div v-if="showDialog" class="dialog-overlay" @click.self="showDialog = false">
        <div class="dialog-card">
          <div class="dialog-header">
            <h3>新增数据源</h3>
            <button class="dialog-close" @click="showDialog = false">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>
          </div>
          <div class="dialog-body">
            <div class="form-group">
              <label class="form-label">名称</label>
              <input v-model="form.name" class="form-input" placeholder="例如：测试数据库" />
            </div>
            <div class="form-group">
              <label class="form-label">类型</label>
              <div class="type-options">
                <button
                  v-for="t in supportedTypes"
                  :key="t.code"
                  type="button"
                  class="type-option"
                  :class="{ active: form.type === t.code }"
                  @click="selectType(t)"
                >
                  <span class="type-option-icon" :style="{ background: typeColor(t.code) }">{{ t.displayName.charAt(0) }}</span>
                  {{ t.displayName }}
                </button>
              </div>
            </div>
            <div class="form-row">
              <div class="form-group flex-2">
                <label class="form-label">主机</label>
                <input v-model="form.host" class="form-input" placeholder="localhost" />
              </div>
              <div class="form-group flex-1">
                <label class="form-label">端口</label>
                <input v-model.number="form.port" type="number" class="form-input" placeholder="3306" min="1" max="65535" />
              </div>
            </div>
            <div class="form-group">
              <label class="form-label">数据库名</label>
              <input v-model="form.databaseName" class="form-input" placeholder="my_database" />
            </div>
            <div class="form-row">
              <div class="form-group flex-1">
                <label class="form-label">用户名</label>
                <input v-model="form.username" class="form-input" placeholder="root" />
              </div>
              <div class="form-group flex-1">
                <label class="form-label">密码</label>
                <input v-model="form.password" type="password" class="form-input" placeholder="密码" />
              </div>
            </div>
          </div>
          <div class="dialog-footer">
            <button class="btn-ghost" @click="showDialog = false">取消</button>
            <button class="btn-primary" @click="handleCreate" :disabled="creating">
              <span v-if="creating">创建中...</span>
              <span v-else>创建</span>
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, onMounted, reactive } from 'vue'
import { useDataSourceStore } from '../stores/datasourceStore'
import { createDataSource, deleteDataSource, testDataSource, getSupportedTypes } from '../api/endpoints'
import { ElMessage, ElMessageBox } from 'element-plus'

const dsStore = useDataSourceStore()
const showDialog = ref(false)
const creating = ref(false)
const testingId = ref(null)
const supportedTypes = ref([])

const form = reactive({
  name: '',
  type: 'mysql',
  host: 'localhost',
  port: 3306,
  databaseName: '',
  username: 'root',
  password: ''
})

const DEFAULT_USERNAMES = { mysql: 'root', postgresql: 'postgres' }
const TYPE_COLORS = { mysql: '#4479a1', postgresql: '#336791' }

function typeColor(code) {
  return TYPE_COLORS[code] || '#64748b'
}

// 各类型的默认用户名，切换类型时仅在用户未改动时跟随切换
function selectType(t) {
  if (form.type !== t.code) {
    const currentDefault = DEFAULT_USERNAMES[form.type] || 'root'
    if (form.username === currentDefault) {
      form.username = DEFAULT_USERNAMES[t.code] || 'root'
    }
    form.type = t.code
    form.port = t.defaultPort
  }
}

async function loadSupportedTypes() {
  try {
    const res = await getSupportedTypes()
    supportedTypes.value = res.data || []
  } catch (e) {
    // 拉取失败时回退为 MySQL 单选项
    supportedTypes.value = [{ code: 'mysql', displayName: 'MySQL', defaultPort: 3306 }]
  }
}

const headerStyle = {
  background: '#f8fafc',
  color: '#475569',
  fontWeight: '600',
  fontSize: '12px',
  textTransform: 'uppercase',
  letterSpacing: '0.3px'
}

async function handleCreate() {
  creating.value = true
  try {
    await createDataSource(form)
    ElMessage.success('数据源创建成功')
    showDialog.value = false
    await dsStore.fetchDataSources()
    Object.assign(form, { name: '', databaseName: '', password: '' })
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
  } finally {
    testingId.value = null
  }
}

async function handleDelete(id) {
  await ElMessageBox.confirm('确定要删除该数据源吗？', '确认', { type: 'warning' })
  await deleteDataSource(id)
  ElMessage.success('删除成功')
  await dsStore.fetchDataSources()
}

onMounted(() => {
  dsStore.fetchDataSources()
  loadSupportedTypes()
})
</script>

<style scoped>
.settings-page {
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
  box-shadow: 0 2px 8px rgba(79, 70, 229, 0.25);
}

.btn-primary:hover:not(:disabled) {
  background: var(--primary-dark);
  box-shadow: 0 4px 12px rgba(79, 70, 229, 0.35);
  transform: translateY(-1px);
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

.table-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  box-shadow: var(--shadow-sm);
}

.table-card :deep(.el-table) {
  --el-table-border-color: var(--border-light);
}

.cell-name {
  display: flex;
  align-items: center;
  gap: 10px;
}

.name-icon {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: var(--primary-bg);
  color: var(--primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.type-badge {
  display: inline-block;
  padding: 2px 8px;
  background: var(--bg);
  color: var(--text-secondary);
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.3px;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: transparent;
  font-size: 12px;
  cursor: pointer;
  transition: all var(--transition);
  color: var(--text-secondary);
}

.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.test-btn:hover:not(:disabled) {
  color: var(--success);
  border-color: var(--success);
  background: var(--success-bg);
}

.delete-btn:hover {
  color: var(--error);
  border-color: var(--error);
  background: var(--error-bg);
}

/* Dialog */
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

.form-input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(79, 70, 229, 0.08);
}

.form-input::placeholder {
  color: var(--text-muted);
}

.type-options {
  display: flex;
  gap: 8px;
}

.type-option {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  font-size: 14px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--transition);
}

.type-option.active {
  border-color: var(--primary);
  background: var(--primary-bg);
  color: var(--primary);
  font-weight: 500;
}

.type-option-icon {
  width: 24px;
  height: 24px;
  border-radius: 5px;
  background: #4477aa;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 24px;
  border-top: 1px solid var(--border);
}
</style>
