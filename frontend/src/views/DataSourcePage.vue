<template>
  <div class="ds-page">
    <!-- ============ 列表视图 ============ -->
    <template v-if="wizardStep === 0">
      <div class="page-header">
        <div>
          <h2 class="page-title">数据源</h2>
          <p class="page-desc">在这里查看和管理您的数据源</p>
        </div>
        <button class="btn-primary" @click="startWizard">
          <el-icon :size="14"><Plus /></el-icon>
          <span>添加数据源</span>
        </button>
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
              <button class="icon-btn" title="配置数据表" @click="openTables(ds)">
                <el-icon :size="14"><Grid /></el-icon>
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
        <button v-if="!keyword.trim()" class="btn-primary sm" @click="startWizard">
          <el-icon :size="14"><Plus /></el-icon>
          <span>添加数据源</span>
        </button>
      </div>
    </template>

    <!-- ============ 向导：选择类型 / 配置连接 / 添加数据表 ============ -->
    <div v-else class="wizard">
      <div class="steps">
        <div v-for="(label, i) in STEP_LABELS" :key="label" class="step-wrap">
          <span v-if="i > 0" class="step-line" :class="{ on: wizardStep > i }"></span>
          <div class="step" :class="{ current: wizardStep === i + 1, done: wizardStep > i + 1 }">
            <span class="step-dot">
              <el-icon v-if="wizardStep > i + 1" :size="12"><Check /></el-icon>
              <template v-else>{{ i + 1 }}</template>
            </span>
            <span class="step-label">{{ label }}</span>
          </div>
        </div>
      </div>

      <!-- 步骤 1：选择类型 -->
      <div v-if="wizardStep === 1" class="wizard-body">
        <div v-for="group in typeGroups" :key="group.title" class="type-group">
          <div class="group-title">
            <el-icon :size="14"><Coin /></el-icon>
            <span>{{ group.title }}</span>
          </div>
          <div class="type-grid">
            <button
              v-for="t in group.types"
              :key="t.code"
              class="type-card"
              :class="{ active: pickedType && pickedType.code === t.code }"
              @click="pickType(t)"
            >
              <span class="type-icon lg" :style="{ background: typeColor(t.code) }">
                {{ t.displayName.charAt(0) }}
              </span>
              <span class="type-text">
                <span class="type-name">{{ t.displayName }}</span>
                <span class="type-hint">{{ t.hint }}</span>
              </span>
            </button>
          </div>
        </div>
      </div>

      <!-- 步骤 2：配置连接 -->
      <div v-else-if="wizardStep === 2" class="wizard-body">
        <div class="connect-card">
          <div class="connect-illustration">
            <span class="type-icon xl" :style="{ background: typeColor(pickedType.code) }">
              {{ pickedType.displayName.charAt(0) }}
            </span>
            <span class="illustration-name">{{ pickedType.displayName }}</span>
          </div>
          <div class="connect-form">
            <div class="form-group">
              <label class="form-label">数据源名称</label>
              <input v-model="form.name" class="form-input" placeholder="输入数据源名称，留空将自动生成" />
            </div>
            <div class="form-row">
              <div class="form-group flex-1">
                <label class="form-label">主机</label>
                <input v-model="form.host" class="form-input" placeholder="输入主机地址" />
              </div>
              <div class="form-group flex-1">
                <label class="form-label">端口</label>
                <input v-model.number="form.port" type="number" class="form-input" :placeholder="String(pickedType.defaultPort || '')" min="1" max="65535" />
              </div>
            </div>
            <div class="form-row">
              <div class="form-group flex-1">
                <label class="form-label">用户名</label>
                <input v-model="form.username" class="form-input" placeholder="输入用户名" />
              </div>
              <div class="form-group flex-1">
                <label class="form-label">密码</label>
                <input v-model="form.password" type="password" class="form-input" placeholder="输入密码" />
              </div>
            </div>
            <div class="form-group">
              <label class="form-label">{{ wizardApiType ? '基础路径' : '数据库 DB' }}</label>
              <input
                v-model="form.databaseName"
                class="form-input"
                :placeholder="wizardApiType ? '接口基础路径，例如 /api' : '输入数据库名称'"
              />
              <span v-if="!wizardApiType" class="field-hint">仅支持单个数据库；留空表示使用连接默认库</span>
              <span v-if="dbNameError" class="field-error">数据库名只支持一个，请不要使用逗号</span>
            </div>
            <div v-if="wizardApiType" class="form-group">
              <label class="form-label">高级配置（JSON：headers / tables）</label>
              <textarea
                v-model="form.extraConfig"
                class="form-input form-textarea"
                rows="4"
                placeholder='{"headers":{"X-API-KEY":"..."},"tables":[{"name":"department_budgets","path":"/departments"}]}'
              ></textarea>
              <span v-if="extraConfigError" class="field-error">JSON 格式不正确</span>
            </div>
            <div class="connect-actions">
              <button class="btn-ghost" @click="wizardStep = 1">上一步</button>
              <button class="btn-primary" :disabled="creating || extraConfigError" @click="submitConnection">
                <span v-if="creating">创建中...</span>
                <span v-else>立即添加</span>
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- 步骤 3：添加数据表 -->
      <div v-else class="wizard-body">
        <div class="tables-head">
          <h3 class="tables-title">选择数据表</h3>
          <span class="tables-note">最多支持 100 张表</span>
        </div>

        <div class="tables-card">
          <div class="db-list">
            <div class="db-item active">{{ currentDbName }}</div>
          </div>

          <div class="tables-main">
            <div class="tabs">
              <span class="tab active">表</span>
            </div>

            <div class="tables-toolbar">
              <div class="filter-input">
                <el-icon :size="15"><Search /></el-icon>
                <input v-model="tableKeyword" class="filter-ipt" placeholder="搜索表" />
              </div>
              <button class="tool-btn" @click="sortAsc = !sortAsc">
                <el-icon :size="14"><Sort /></el-icon>
                <span>排序</span>
              </button>
              <label class="tool-check">
                <input type="checkbox" :checked="allSelected" @change="toggleAll($event.target.checked)" />
                <span>全选</span>
              </label>
            </div>

            <div v-if="tablesLoading" class="tables-state">正在读取数据表...</div>
            <div v-else-if="tablesError" class="tables-state error">
              <span>{{ tablesError }}</span>
              <button class="btn-ghost sm" @click="loadTables(createdDs.id)">重试</button>
            </div>
            <div v-else-if="visibleTables.length === 0" class="tables-state">
              {{ tableKeyword.trim() ? '没有匹配的表' : '该数据源下没有读到表' }}
            </div>
            <div v-else class="table-grid">
              <label v-for="t in visibleTables" :key="t.tableName" class="table-item">
                <input
                  type="checkbox"
                  :checked="selectedTables.has(t.tableName)"
                  @change="toggleTable(t.tableName, $event.target.checked)"
                />
                <span class="table-name">{{ t.tableName }}</span>
                <span v-if="t.comment" class="table-comment">{{ t.comment }}</span>
              </label>
            </div>

            <div class="tables-footer">
              <button class="btn-ghost" @click="finishWizard">跳过</button>
              <button class="btn-primary" :disabled="savingTables" @click="saveTables">
                <span v-if="savingTables">保存中...</span>
                <span v-else>确认选择 ({{ selectedTables.size }})</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ============ 编辑弹窗（新增走向导，编辑沿用弹窗） ============ -->
    <Teleport to="body">
      <div v-if="showDialog" class="dialog-overlay" @click.self="closeDialog">
        <div class="dialog-card">
          <div class="dialog-header">
            <h3>编辑 {{ dialogType.displayName }} 数据源</h3>
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
              <label class="form-label">{{ dialogApiType ? '基础路径' : '数据库名' }}</label>
              <input v-model="form.databaseName" class="form-input" :placeholder="dialogApiType ? '/api' : 'my_database'" />
              <span v-if="!dialogApiType" class="field-hint">仅支持单个数据库；留空表示使用连接默认库</span>
              <span v-if="dbNameError" class="field-error">数据库名只支持一个，请不要使用逗号</span>
            </div>
            <div class="form-row">
              <div class="form-group flex-1">
                <label class="form-label">用户名</label>
                <input v-model="form.username" class="form-input" :placeholder="defaultUsername" />
              </div>
              <div class="form-group flex-1">
                <label class="form-label">密码</label>
                <input v-model="form.password" type="password" class="form-input" placeholder="留空表示不修改" />
              </div>
            </div>
            <div v-if="dialogApiType" class="form-group">
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
            <button class="btn-primary" @click="submitEdit" :disabled="creating || extraConfigError">
              <span v-if="creating">保存中...</span>
              <span v-else>保存</span>
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
  Plus, Search, EditPen, Delete, Connection, Loading, Close, CircleCheck, Check, Coin, Sort, Grid
} from '@element-plus/icons-vue'
import { useDataSourceStore } from '../stores/datasourceStore'
import {
  createDataSource, updateDataSource, deleteDataSource, testDataSource, getTables
} from '../api/endpoints'

const dsStore = useDataSourceStore()

const STEP_LABELS = ['选择类型', '配置连接', '添加数据表']

/** 0 = 列表；1/2/3 = 向导步骤 */
const wizardStep = ref(0)
const pickedType = ref(null)
/** 第 3 步要配置表的数据源（新增时是刚创建的那条，从列表点进来则是既有那条） */
const createdDs = ref(null)

const showDialog = ref(false)
const creating = ref(false)
const savingTables = ref(false)
const testingId = ref(null)
const keyword = ref('')
/** 最近一次测试连接成功的 id（仅页面内存态，不落库、刷新即清） */
const testedOk = ref(new Set())
/** 当前编辑的数据源 id */
const editingId = ref(null)
const dialogType = ref({ code: 'mysql', displayName: 'MySQL', defaultPort: 3306 })

// 第 3 步状态
const tables = ref([])
const tablesLoading = ref(false)
const tablesError = ref('')
const selectedTables = ref(new Set())
const tableKeyword = ref('')
const sortAsc = ref(true)

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

// 卡片副文案（截图里是版本号，我们换成连接方式与默认端口）
const TYPE_HINTS = {
  mysql: '关系型数据库 · 3306',
  postgresql: '关系型数据库 · 5432',
  hive: 'HiveServer2 · 10000',
  hdfs: 'WebHDFS 目录 · 9870',
  rest: 'REST 接口',
  graphql: 'GraphQL 接口'
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

const TYPE_GROUPS = [
  { title: '数据库', codes: ['mysql', 'postgresql'] },
  { title: '大数据', codes: ['hive', 'hdfs'] },
  { title: 'API', codes: ['rest', 'graphql'] }
]

const typeOptions = computed(() => (dsStore.supportedTypes.length ? dsStore.supportedTypes : FALLBACK_TYPES))
const dataSources = computed(() => dsStore.dataSources)

const typeGroups = computed(() =>
  TYPE_GROUPS.map((g) => ({
    title: g.title,
    types: g.codes
      .map((code) => typeOptions.value.find((t) => t.code === code))
      .filter(Boolean)
      .map((t) => ({ ...t, hint: TYPE_HINTS[t.code] || '' }))
  })).filter((g) => g.types.length > 0)
)

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return dataSources.value
  return dataSources.value.filter((ds) => `${ds.name || ''} ${ds.type || ''}`.toLowerCase().includes(kw))
})

const visibleTables = computed(() => {
  const kw = tableKeyword.value.trim().toLowerCase()
  const list = kw ? tables.value.filter((t) => (t.tableName || '').toLowerCase().includes(kw)) : [...tables.value]
  return list.sort((a, b) => (sortAsc.value ? 1 : -1) * String(a.tableName).localeCompare(String(b.tableName)))
})

const allSelected = computed(() =>
  visibleTables.value.length > 0 && visibleTables.value.every((t) => selectedTables.value.has(t.tableName))
)

const currentDbName = computed(() => createdDs.value?.databaseName || '默认库')

/** 向导第 2 步按所选类型判断；编辑弹窗按 dialogType 判断 */
const wizardApiType = computed(() => isApiTypeCode(pickedType.value?.code))
const dialogApiType = computed(() => isApiTypeCode(dialogType.value.code))
/** JSON 校验只针对当前正在编辑的表单，两个入口取或即可 */
const formApiType = computed(() => wizardApiType.value || dialogApiType.value)

const DEFAULT_USERNAMES = { mysql: 'root', postgresql: 'postgres' }
const defaultUsername = computed(() => DEFAULT_USERNAMES[dialogType.value.code] || '')

const extraConfigError = computed(() => {
  if (!formApiType.value || !form.extraConfig.trim()) return false
  try {
    JSON.parse(form.extraConfig)
    return false
  } catch {
    return true
  }
})

// 数据库名只支持单个：逗号（中英文）视为误输入，提交前拦下
const dbNameError = computed(() => /[,，]/.test(form.databaseName || ''))

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

/* ---------------- 向导 ---------------- */

function startWizard() {
  pickedType.value = null
  createdDs.value = null
  wizardStep.value = 1
}

function pickType(t) {
  pickedType.value = t
  Object.assign(form, {
    name: '',
    host: 'localhost',
    port: t.defaultPort || 3306,
    databaseName: isApiTypeCode(t.code) ? '/api' : '',
    username: DEFAULT_USERNAMES[t.code] || '',
    password: '',
    extraConfig: ''
  })
  wizardStep.value = 2
}

/** 第 2 步提交：先落库，再进入选表步骤 */
async function submitConnection() {
  if (!form.host.trim()) {
    ElMessage.warning('请填写主机')
    return
  }
  if (dbNameError.value) {
    ElMessage.warning('数据库名只支持一个，请不要使用逗号')
    return
  }
  creating.value = true
  try {
    const payload = {
      // 名称留空时按类型自动生成
      name: form.name.trim() || `${pickedType.value.displayName}数据源`,
      type: pickedType.value.code,
      host: form.host,
      port: form.port,
      databaseName: form.databaseName,
      username: form.username,
      password: form.password || '******',
      extraConfig: isApiTypeCode(pickedType.value.code) ? form.extraConfig.trim() || null : null
    }
    const res = await createDataSource(payload)
    createdDs.value = res.data
    await dsStore.fetchDataSources()
    selectedTables.value = new Set()
    tableKeyword.value = ''
    wizardStep.value = 3
    await loadTables(createdDs.value.id)
  } catch (e) {
    // error handled by interceptor
  } finally {
    creating.value = false
  }
}

/** 从列表的「配置数据表」进入第 3 步 */
async function openTables(ds) {
  createdDs.value = ds
  pickedType.value = typeOptions.value.find((t) => t.code === ds.type) || null
  selectedTables.value = new Set(readSelectedTables(ds))
  tableKeyword.value = ''
  sortAsc.value = true
  wizardStep.value = 3
  await loadTables(ds.id)
}

/** 已保存的表选择：存在 extra_config.selectedTables（后端会把它并入适配器配置，JDBC 侧忽略） */
function readSelectedTables(ds) {
  if (!ds.extraConfig) return []
  try {
    const parsed = JSON.parse(ds.extraConfig)
    return Array.isArray(parsed.selectedTables) ? parsed.selectedTables : []
  } catch {
    return []
  }
}

async function loadTables(datasourceId) {
  tablesLoading.value = true
  tablesError.value = ''
  try {
    const res = await getTables(datasourceId)
    tables.value = res.data || []
  } catch (e) {
    tables.value = []
    tablesError.value = '读取数据表失败，请确认连接配置是否正确'
  } finally {
    tablesLoading.value = false
  }
}

function toggleTable(name, checked) {
  const next = new Set(selectedTables.value)
  if (checked) next.add(name)
  else next.delete(name)
  selectedTables.value = next
}

function toggleAll(checked) {
  const next = new Set(selectedTables.value)
  visibleTables.value.forEach((t) => (checked ? next.add(t.tableName) : next.delete(t.tableName)))
  selectedTables.value = next
}

/** 保存表选择：写回 extra_config.selectedTables（保留其余配置键） */
async function saveTables() {
  const ds = createdDs.value
  if (!ds) return
  savingTables.value = true
  try {
    let extra = {}
    if (ds.extraConfig) {
      try {
        extra = JSON.parse(ds.extraConfig) || {}
      } catch {
        extra = {}
      }
    }
    extra.selectedTables = [...selectedTables.value]
    await updateDataSource(ds.id, {
      name: ds.name,
      type: ds.type,
      host: ds.host,
      port: ds.port,
      databaseName: ds.databaseName,
      username: ds.username,
      password: '******',
      extraConfig: JSON.stringify(extra)
    })
    ElMessage.success('数据表已保存')
    await dsStore.fetchDataSources()
    finishWizard()
  } catch (e) {
    // error handled by interceptor
  } finally {
    savingTables.value = false
  }
}

function finishWizard() {
  wizardStep.value = 0
  createdDs.value = null
  pickedType.value = null
  tables.value = []
}

/* ---------------- 编辑弹窗 ---------------- */

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

async function submitEdit() {
  if (!form.name.trim() || !form.host.trim()) {
    ElMessage.warning('请填写名称与主机')
    return
  }
  if (dbNameError.value) {
    ElMessage.warning('数据库名只支持一个，请不要使用逗号')
    return
  }
  creating.value = true
  try {
    const payload = {
      name: form.name,
      type: dialogType.value.code,
      host: form.host,
      port: form.port,
      databaseName: form.databaseName,
      username: form.username,
      password: form.password || '******',
      extraConfig: dialogApiType.value ? form.extraConfig.trim() : null
    }
    await updateDataSource(editingId.value, payload)
    ElMessage.success('数据源已更新')
    closeDialog()
    await dsStore.fetchDataSources()
  } catch (e) {
    // error handled by interceptor
  } finally {
    creating.value = false
  }
}

/* ---------------- 列表操作 ---------------- */

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

.type-icon.lg {
  width: 44px;
  height: 44px;
  font-size: 19px;
  border-radius: 11px;
}

.type-icon.xl {
  width: 120px;
  height: 120px;
  border-radius: 28px;
  font-size: 52px;
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

/* ---- 向导骨架 ---- */
.wizard {
  max-width: 1180px;
  margin: 0 auto;
}

.steps {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 6px 0 26px;
}

.step-wrap {
  display: flex;
  align-items: center;
  gap: 4px;
}

.step {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-muted);
  font-size: 13.5px;
}

.step.current,
.step.done {
  color: var(--text);
}

.step-dot {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--border);
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.step.current .step-dot,
.step.done .step-dot {
  background: var(--primary);
  color: #fff;
}

.step-line {
  width: 56px;
  height: 1px;
  background: var(--border);
  margin: 0 10px;
}

.step-line.on {
  background: var(--primary-light);
}

/* ---- 步骤 1：类型网格 ---- */
.type-group {
  margin-bottom: 22px;
}

.group-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--text-secondary);
}

.type-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: 12px;
}

.type-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--surface);
  text-align: left;
  cursor: pointer;
  transition: all var(--transition);
}

.type-card:hover {
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.07);
}

.type-card.active {
  border-color: var(--primary);
}

.type-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.type-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}

.type-hint {
  font-size: 12px;
  color: var(--text-muted);
}

/* ---- 步骤 2：连接表单 ---- */
.connect-card {
  display: grid;
  grid-template-columns: 300px 1fr;
  gap: 24px;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--surface);
  padding: 28px 32px;
}

.connect-illustration {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
}

.illustration-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-secondary);
}

.connect-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.connect-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
}

/* ---- 步骤 3：选表 ---- */
.tables-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 12px;
}

.tables-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text);
}

.tables-note {
  font-size: 12.5px;
  color: var(--text-muted);
}

.tables-card {
  display: grid;
  grid-template-columns: 200px 1fr;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--surface);
  overflow: hidden;
  min-height: 420px;
}

.db-list {
  border-right: 1px solid var(--border-light);
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.db-item {
  padding: 7px 10px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--text-secondary);
  cursor: default;
}

.db-item.active {
  background: var(--bg);
  color: var(--text);
  font-weight: 500;
}

.tables-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.tabs {
  display: flex;
  gap: 4px;
  padding: 10px 14px 0;
  border-bottom: 1px solid var(--border-light);
}

.tab {
  padding: 8px 14px;
  font-size: 13px;
  color: var(--text-muted);
  border-bottom: 2px solid transparent;
}

.tab.active {
  color: var(--text);
  font-weight: 600;
  border-bottom-color: var(--primary);
}

.tables-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
}

.tool-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all var(--transition);
}

.tool-btn:hover {
  background: var(--bg);
  color: var(--text);
}

.tool-check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
  white-space: nowrap;
}

.tables-state {
  padding: 60px 0;
  text-align: center;
  font-size: 13px;
  color: var(--text-muted);
}

.tables-state.error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.table-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 2px 16px;
  padding: 4px 14px 16px;
  overflow-y: auto;
  flex: 1;
}

.table-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 6px;
  font-size: 13px;
  color: var(--text);
  cursor: pointer;
  border-radius: 6px;
  min-width: 0;
}

.table-item:hover {
  background: var(--surface-hover);
}

.table-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-comment {
  font-size: 11.5px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tables-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 14px;
  border-top: 1px solid var(--border-light);
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
  padding: 9px 16px;
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  font-size: 13.5px;
  cursor: pointer;
  transition: all var(--transition);
}

.btn-ghost.sm {
  padding: 6px 12px;
  font-size: 12.5px;
}

.btn-ghost:hover {
  background: var(--bg);
  border-color: #cbd5e1;
}

/* ---- 表单 ---- */
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

.field-hint {
  font-size: 12px;
  color: var(--text-muted);
}

/* ---- 编辑弹窗 ---- */
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

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 24px;
  border-top: 1px solid var(--border);
}
</style>
