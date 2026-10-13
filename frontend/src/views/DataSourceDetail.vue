<template>
  <div class="ds-detail">
    <!-- ============ 顶部：面包屑 + 连接状态 / 刷新 ============ -->
    <div class="detail-header">
      <div class="breadcrumb">
        <button class="crumb-link" @click="backToList">全部数据源</button>
        <el-icon :size="13" class="crumb-sep"><ArrowRight /></el-icon>
        <svg v-if="brandOf(ds?.type)" class="crumb-logo" :viewBox="brandOf(ds?.type).viewBox" fill="currentColor" aria-hidden="true">
          <path :d="brandOf(ds?.type).path" />
        </svg>
        <span class="crumb-current">{{ ds ? ds.name : '数据源' }}</span>
      </div>
      <div class="header-actions">
        <span class="conn-status" :class="connStatus || 'idle'">
          <el-icon v-if="connStatus === 'testing'" :size="13" class="spinning"><Loading /></el-icon>
          <el-icon v-else-if="connStatus === 'ok'" :size="13"><CircleCheck /></el-icon>
          <el-icon v-else-if="connStatus === 'fail'" :size="13"><CircleClose /></el-icon>
          <span>{{ CONN_LABELS[connStatus] || CONN_LABELS.idle }}</span>
        </span>
        <button class="ghost-btn" :disabled="connStatus === 'testing'" @click="dsStore.checkOne(props.datasourceId)">
          <el-icon :size="13"><Connection /></el-icon>
          <span>测试连接</span>
        </button>
        <button class="ghost-btn" :disabled="refreshing" @click="handleRefresh">
          <el-icon :size="13" :class="{ spinning: refreshing }"><Refresh /></el-icon>
          <span>重新同步</span>
        </button>
      </div>
    </div>

    <div v-if="loading" class="detail-loading" v-loading="true" element-loading-text="正在读取表结构..."></div>

    <div v-else-if="!ds" class="detail-empty">
      <p>这个数据源不存在，可能已被删除</p>
      <button class="ghost-btn" @click="backToList">
        <el-icon :size="13"><ArrowLeft /></el-icon>
        <span>返回数据源列表</span>
      </button>
    </div>

    <div v-else class="detail-body">
      <!-- ============ 左栏：导航 + 搜索 + 数据源 → 表 树 ============ -->
      <aside class="detail-rail">
        <div class="rail-nav">
          <button
            type="button"
            class="rail-nav-item"
            :class="{ active: !selectedTable }"
            @click="selectDatabase"
          >
            <el-icon :size="14"><InfoFilled /></el-icon>
            <span>基本信息</span>
          </button>
        </div>

        <div class="rail-search">
          <el-icon :size="14"><Search /></el-icon>
          <input v-model="keyword" class="rail-search-input" placeholder="搜索表" />
        </div>

        <div class="rail-tree">
          <button
            type="button"
            class="tree-root"
            :class="{ active: !selectedTable }"
            @click="selectDatabase"
          >
            <svg v-if="brandOf(ds.type)" class="tree-logo" :viewBox="brandOf(ds.type).viewBox" fill="currentColor" aria-hidden="true">
              <path :d="brandOf(ds.type).path" />
            </svg>
            <span class="tree-root-name">{{ ds.databaseName || ds.name }}</span>
            <span class="tree-count">{{ tables.length }}</span>
          </button>
          <div class="tree-tables">
            <button
              v-for="t in filteredTables"
              :key="t.tableName"
              type="button"
              class="tree-item"
              :class="{ active: t.tableName === selectedTable }"
              @click="selectTable(t.tableName)"
            >
              <el-icon :size="13" class="tree-item-icon"><Grid /></el-icon>
              <span class="tree-item-name">{{ t.tableName }}</span>
              <span class="tree-item-count">{{ t.columnCount ?? '-' }}</span>
            </button>
            <div v-if="filteredTables.length === 0" class="tree-empty">
              {{ tables.length === 0 ? '没有读到表' : '没有匹配的表' }}
            </div>
          </div>
        </div>
      </aside>

      <!-- ============ 右栏：基本信息 / 表详情 ============ -->
      <section class="detail-content">
        <!-- 基本信息：库名 + 表数/列数 + 表清单 -->
        <template v-if="!selectedTable">
          <div class="content-title">
            <span class="content-title-main">{{ ds.databaseName || ds.name }}</span>
            <span class="content-title-meta">{{ tables.length }} 张表 · {{ totalColumns }} 个列</span>
          </div>
          <div class="table-list">
            <div class="table-list-head">
              <span class="col-name">表名</span>
              <span class="col-comment">备注</span>
              <span class="col-count">列数</span>
            </div>
            <button
              v-for="t in filteredTables"
              :key="t.tableName"
              type="button"
              class="table-list-row"
              @click="selectTable(t.tableName)"
            >
              <span class="col-name">{{ t.tableName }}</span>
              <span class="col-comment">
                <el-icon :size="13" class="cell-icon"><Tickets /></el-icon>
                <span>{{ t.comment || '-' }}</span>
              </span>
              <span class="col-count">{{ t.columnCount ?? '-' }}</span>
            </button>
            <div v-if="filteredTables.length === 0" class="table-list-empty">
              {{ tables.length === 0 ? '这个数据源没有读到表结构' : '没有匹配的表' }}
            </div>
          </div>
        </template>

        <!-- 表详情：字段结构 / 数据预览 -->
        <template v-else>
          <div class="table-head">
            <div class="table-head-fields">
              <label class="field">
                <span class="field-label">表名</span>
                <span class="field-value">{{ selectedTable }}</span>
              </label>
              <label class="field">
                <span class="field-label">备注</span>
                <span class="field-value">{{ selectedTableComment || '-' }}</span>
              </label>
            </div>
            <button class="ghost-btn" :disabled="refreshing" @click="handleRefresh">
              <el-icon :size="13" :class="{ spinning: refreshing }"><Refresh /></el-icon>
              <span>重新同步</span>
            </button>
          </div>

          <div class="tabs">
            <button class="tab" :class="{ active: tab === 'columns' }" @click="tab = 'columns'">字段结构</button>
            <button class="tab" :class="{ active: tab === 'preview' }" @click="openPreview">数据预览</button>
          </div>

          <!-- 字段结构 -->
          <div v-if="tab === 'columns'" class="columns-panel" v-loading="columnsLoading">
            <div class="grid-head">
              <span class="c-col">列名</span>
              <span class="c-comment"><span>备注</span></span>
              <span class="c-semantic">语义类型</span>
            </div>
            <template v-for="col in columns" :key="col.columnName">
              <div class="grid-row">
                <button class="c-col cell-name" type="button" @click="toggleExpand(col.columnName)">
                  <el-icon :size="12" class="caret" :class="{ open: expanded.has(col.columnName) }"><CaretRight /></el-icon>
                  <span>{{ col.columnName }}</span>
                </button>
                <span class="c-comment">
                  <el-icon :size="13" class="cell-icon"><Tickets /></el-icon>
                  <span>{{ col.comment || '-' }}</span>
                </span>
                <span class="c-semantic">{{ semanticType(col) }}</span>
              </div>
              <div v-if="expanded.has(col.columnName)" class="grid-row-detail">
                <span class="detail-pair"><b>数据类型</b>{{ col.dataType || '-' }}</span>
                <span class="detail-pair"><b>完整类型</b>{{ col.columnType || '-' }}</span>
                <span class="detail-pair"><b>键</b>{{ col.columnKey || '-' }}</span>
                <span class="detail-pair"><b>可空</b>{{ col.isNullable || '-' }}</span>
                <span class="detail-pair"><b>默认值</b>{{ col.defaultValue ?? '-' }}</span>
              </div>
            </template>
            <div v-if="columns.length === 0 && !columnsLoading" class="columns-empty">没有读到字段信息</div>
          </div>

          <!-- 数据预览：整块装进带边框的容器，50 条/页，底部页码 -->
          <div v-else class="preview-panel">
            <template v-if="preview && preview.success">
              <div class="preview-box">
                <div class="preview-table" v-loading="previewLoading">
                  <el-table :data="preview.rows" size="small" max-height="460" style="width: 100%">
                    <el-table-column type="index" label="#" width="48" align="center" />
                    <el-table-column
                      v-for="c in preview.columns"
                      :key="c"
                      :prop="c"
                      :label="c"
                      min-width="150"
                      show-overflow-tooltip
                    />
                  </el-table>
                </div>
                <div class="preview-footer">
                  <span class="preview-range">
                    {{ rangeStart }}-{{ rangeEnd }} / 共 {{ previewTotal }} 条
                    <span class="preview-cost">耗时 {{ preview.executionTimeMs }} ms</span>
                  </span>
                  <el-pagination
                    small
                    background
                    layout="prev, pager, next"
                    :current-page="preview.page"
                    :page-size="PREVIEW_PAGE_SIZE"
                    :total="previewTotal"
                    @current-change="changePreviewPage"
                  />
                </div>
              </div>
            </template>
            <div v-else-if="preview && !preview.success" class="preview-error">
              {{ preview.errorMessage || '预览失败' }}
            </div>
            <div v-else-if="previewLoading" class="preview-box preview-loading" v-loading="true"></div>
            <div v-else class="columns-empty">暂无数据</div>
          </div>
        </template>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft, ArrowRight, Search, Refresh, Connection, Loading, Tickets, Grid, InfoFilled, CaretRight,
  CircleCheck, CircleClose
} from '@element-plus/icons-vue'
import { BRAND_ICONS } from '../assets/brandIcons'
import { useDataSourceStore, CONN_LABELS } from '../stores/datasourceStore'
import { getTables, getColumns, refreshMetadata, getTablePreview } from '../api/endpoints'

const props = defineProps({
  datasourceId: { type: Number, required: true }
})

const router = useRouter()
const dsStore = useDataSourceStore()

const loading = ref(true)
const refreshing = ref(false)
const tables = ref([])
const keyword = ref('')
const selectedTable = ref(null)
const tab = ref('columns')

/** 列表/向导里刚测试过或加载过的数据源直接用；直接输 URL 进来时 store 可能为空 */
const ds = computed(() => dsStore.dataSources.find((d) => d.id === props.datasourceId) || null)

/** 连接情况取自 store（列表页、选择器共用同一份探测结果） */
const connStatus = computed(() => dsStore.connStatus[props.datasourceId] || null)

const filteredTables = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return tables.value
  return tables.value.filter((t) => `${t.tableName || ''} ${t.comment || ''}`.toLowerCase().includes(kw))
})

/** 后端 getTables 是精简返回，列数来自新增的 columnCount；旧后端没有该字段时显示 '-' */
const totalColumns = computed(() => {
  const known = tables.value.filter((t) => typeof t.columnCount === 'number')
  if (known.length !== tables.value.length) return '-'
  return known.reduce((sum, t) => sum + t.columnCount, 0)
})

const selectedTableComment = computed(
  () => tables.value.find((t) => t.tableName === selectedTable.value)?.comment || ''
)

/* ---------------- 列信息 / 预览：按表懒加载 + 本地缓存 ---------------- */

const columnCache = reactive({})
const columnsLoading = ref(false)

const columns = computed(() => columnCache[selectedTable.value] || [])

/* 预览分页：每页 50 条，总行数由后端 COUNT(*) 带出；状态按表保留，切表再切回不重拉 */
const PREVIEW_PAGE_SIZE = 50
const previewState = reactive({})
const previewLoading = ref(false)

const preview = computed(() => previewState[selectedTable.value] || null)
const previewTotal = computed(() => preview.value?.total || 0)
const rangeStart = computed(() =>
  previewTotal.value === 0 ? 0 : (preview.value.page - 1) * PREVIEW_PAGE_SIZE + 1
)
const rangeEnd = computed(
  () => (preview.value.page - 1) * PREVIEW_PAGE_SIZE + (preview.value.rows?.length || 0)
)

/** 展开的列（看数据类型/可空/默认值） */
const expanded = ref(new Set())

function toggleExpand(columnName) {
  const next = new Set(expanded.value)
  if (next.has(columnName)) next.delete(columnName)
  else next.add(columnName)
  expanded.value = next
}

async function ensureColumns() {
  const table = selectedTable.value
  if (!table || columnCache[table]) return
  columnsLoading.value = true
  try {
    // 拦截器返回的是 {code,message,data} 信封，取 data
    const res = await getColumns(props.datasourceId, table)
    columnCache[table] = res.data || []
  } catch (e) {
    columnCache[table] = []
  } finally {
    columnsLoading.value = false
  }
}

async function loadPreview(table, page) {
  previewLoading.value = true
  try {
    // 拦截器返回 {code,message,data} 信封；data.total 是整表行数，
    // 旧后端没有该字段时退化为本页行数（分页退化成单页，不报错）
    const res = await getTablePreview(props.datasourceId, table, page, PREVIEW_PAGE_SIZE)
    const data = res.data
    if (data && data.success) {
      previewState[table] = {
        page,
        total: typeof data.total === 'number' ? data.total : (data.rows?.length || 0),
        rows: data.rows || [],
        columns: data.columns || [],
        executionTimeMs: data.executionTimeMs,
        success: true
      }
    } else {
      previewState[table] = { page, success: false, errorMessage: data?.errorMessage || '预览失败' }
    }
  } catch (e) {
    previewState[table] = { page, success: false, errorMessage: '预览请求失败' }
  } finally {
    previewLoading.value = false
  }
}

function openPreview() {
  tab.value = 'preview'
  const table = selectedTable.value
  if (!table || previewState[table]) return
  loadPreview(table, 1)
}

function changePreviewPage(page) {
  const table = selectedTable.value
  if (!table) return
  loadPreview(table, page)
}

/** 回到库级视图（基本信息）：清掉当前表选择，右栏切回表清单 */
function selectDatabase() {
  selectedTable.value = null
  expanded.value = new Set()
}

function selectTable(tableName) {
  if (selectedTable.value === tableName) return
  selectedTable.value = tableName
  tab.value = 'columns'
  expanded.value = new Set()
  ensureColumns()
}

/* ---------------- 语义类型：前端规则派生 ---------------- */

// 后端 ColumnInfo 没有语义类型字段，按列名/主键标识做规则推断（与截图里的取值口径一致）
const SEMANTIC_RULES = [
  [/^(title|name|subject|headline)$/, '标题'],
  [/^(description|desc|content|summary|remark|note|comments?)$/, '描述'],
  [/^(tags?|categor(y|ies)|type|status|level|state|kind)$/, '分类'],
  [/(url|link|image|img|avatar|path|cover|icon)$/, '链接'],
  [/^(created_at|created_time|create_time|gmt_create|created)$/, '创建时间戳'],
  [/^(updated_at|updated_time|update_time|gmt_modified|updated|deleted_at)$/, '更新时间戳']
]

function semanticType(col) {
  if (col.columnKey === 'PRI') return '主键'
  const name = String(col.columnName || '').toLowerCase()
  const hit = SEMANTIC_RULES.find(([re]) => re.test(name))
  return hit ? hit[1] : '-'
}

function brandOf(code) {
  return BRAND_ICONS[code] || null
}

/* ---------------- 加载与刷新 ---------------- */

async function loadTables() {
  try {
    const res = await getTables(props.datasourceId)
    tables.value = res.data || []
  } catch (e) {
    tables.value = []
  }
}

async function handleRefresh() {
  refreshing.value = true
  try {
    await refreshMetadata(props.datasourceId)
    Object.keys(columnCache).forEach((k) => delete columnCache[k])
    Object.keys(previewState).forEach((k) => delete previewState[k])
    await loadTables()
    if (selectedTable.value) {
      await ensureColumns()
      if (tab.value === 'preview') await loadPreview(selectedTable.value, 1)
    }
    ElMessage.success('表结构已重新同步')
  } catch (e) {
    // 拦截器已提示
  } finally {
    refreshing.value = false
  }
}

/** 测连接：失败语义是「HTTP 200 + 业务码 500」，拦截器已按 silent 跳过提示，这里落状态 */
function testConnection() {
  dsStore.checkOne(props.datasourceId)
}

function backToList() {
  router.push('/datasources')
}

onMounted(async () => {
  if (!dsStore.dataSources.length) await dsStore.fetchDataSources()
  await loadTables()
  loading.value = false
  testConnection()
})

// 换数据源（同路由不同 id）时整体重置，避免残留上一张表的选择
watch(() => props.datasourceId, () => {
  selectedTable.value = null
  keyword.value = ''
  expanded.value = new Set()
  Object.keys(columnCache).forEach((k) => delete columnCache[k])
  Object.keys(previewState).forEach((k) => delete previewState[k])
  loadTables()
})
</script>

<style scoped>
.ds-detail {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--surface);
}

/* ---- 顶部 ---- */
.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 24px;
  border-bottom: 1px solid var(--border-light);
  flex-shrink: 0;
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.crumb-link {
  border: none;
  background: transparent;
  padding: 0;
  font-family: var(--font-sans);
  font-size: 13.5px;
  color: var(--text-muted);
  cursor: pointer;
  transition: color var(--transition);
}

.crumb-link:hover {
  color: var(--primary);
}

.crumb-sep {
  color: var(--text-muted);
  flex-shrink: 0;
}

.crumb-logo {
  width: 15px;
  height: 15px;
  color: var(--text);
  flex-shrink: 0;
}

.crumb-current {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

/* 连接情况标签：与数据源列表同一套配色（正常=绿，失败=红） */
.conn-status {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
  color: var(--text-muted);
  background: var(--bg);
}

.conn-status.ok {
  color: var(--ok);
  background: var(--ok-bg);
}

.conn-status.fail {
  color: var(--error);
  background: var(--error-bg);
}

.ghost-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--text-secondary);
  font-family: var(--font-sans);
  font-size: 12.5px;
  cursor: pointer;
  transition: all var(--transition);
}

.ghost-btn:hover:not(:disabled) {
  border-color: var(--primary-light);
  color: var(--primary);
}

.ghost-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.spinning {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.detail-loading {
  flex: 1;
  min-height: 240px;
}

.detail-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  font-size: 13.5px;
  color: var(--text-muted);
}

/* ---- 主体：左栏 + 右栏 ---- */
.detail-body {
  flex: 1;
  min-height: 0;
  display: flex;
}

.detail-rail {
  width: 248px;
  flex-shrink: 0;
  border-right: 1px solid var(--border-light);
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow-y: auto;
}

.rail-nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 10px;
  border: none;
  border-radius: var(--radius);
  background: transparent;
  text-align: left;
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background var(--transition);
}

.rail-nav-item:hover {
  background: var(--primary-bg);
}

.rail-nav-item.active {
  background: var(--primary-bg);
  color: var(--primary);
  font-weight: 600;
}

.rail-search {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  color: var(--text-muted);
}

.rail-search-input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--text);
}

.rail-search-input::placeholder {
  color: var(--text-muted);
}

.rail-tree {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-height: 0;
}

.tree-root {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 10px;
  border: none;
  border-radius: var(--radius);
  background: var(--surface-hover);
  text-align: left;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
  cursor: pointer;
  transition: background var(--transition);
}

.tree-root:hover,
.tree-root.active {
  background: var(--primary-bg);
}

.tree-root.active {
  color: var(--primary);
}

.tree-logo {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

.tree-root-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-count {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 500;
  color: var(--text-muted);
  background: var(--surface);
  border-radius: 9px;
  padding: 0 7px;
}

.tree-tables {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding-left: 6px;
}

.tree-item {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  padding: 6px 8px;
  border: none;
  border-radius: var(--radius);
  background: transparent;
  text-align: left;
  font-family: var(--font-sans);
  font-size: 12.5px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background var(--transition);
}

.tree-item:hover {
  background: var(--bg);
}

.tree-item.active {
  background: var(--primary-bg);
  color: var(--primary);
  font-weight: 600;
}

.tree-item-icon {
  color: var(--text-muted);
  flex-shrink: 0;
}

.tree-item-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-item-count {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-muted);
}

.tree-empty {
  padding: 10px;
  font-size: 12.5px;
  color: var(--text-muted);
  text-align: center;
}

.detail-content {
  flex: 1;
  min-width: 0;
  padding: 20px 24px 28px;
  overflow-y: auto;
}

.content-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
}

.content-title-main {
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
}

.content-title-meta {
  font-size: 12.5px;
  color: var(--text-muted);
}

/* ---- 表清单 ---- */
.table-list {
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.table-list-head,
.table-list-row {
  display: grid;
  grid-template-columns: minmax(180px, 1.2fr) minmax(200px, 2fr) 90px;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
}

.table-list-head {
  background: #f8fafc;
  border-bottom: 1px solid var(--border);
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
}

.table-list-row {
  width: 100%;
  border: none;
  border-top: 1px solid var(--border-light);
  background: var(--surface);
  text-align: left;
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--text);
  cursor: pointer;
  transition: background var(--transition);
}

.table-list-row:first-of-type {
  border-top: none;
}

.table-list-row:hover {
  background: var(--primary-bg);
}

.table-list-empty {
  padding: 22px;
  text-align: center;
  font-size: 13px;
  color: var(--text-muted);
}

.col-name {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}

.col-comment {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  color: var(--text-secondary);
}

.col-comment span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.col-count {
  text-align: right;
  color: var(--text-secondary);
}

.cell-icon {
  color: var(--text-muted);
  flex-shrink: 0;
}

/* ---- 表详情 ---- */
.table-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}

.table-head-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 28px;
  min-width: 0;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 160px;
}

.field-label {
  font-size: 12px;
  color: var(--text-muted);
}

.field-value {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}

.tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 4px;
}

.tab {
  padding: 8px 4px;
  margin-right: 18px;
  border: none;
  border-bottom: 2px solid transparent;
  background: transparent;
  font-family: var(--font-sans);
  font-size: 13.5px;
  color: var(--text-muted);
  cursor: pointer;
  transition: all var(--transition);
}

.tab:hover {
  color: var(--text);
}

.tab.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
  font-weight: 600;
}

/* ---- 字段结构 ---- */
.columns-panel {
  min-height: 120px;
}

.grid-head,
.grid-row {
  display: grid;
  grid-template-columns: minmax(170px, 1.1fr) minmax(190px, 2fr) 120px;
  align-items: center;
  gap: 12px;
  padding: 9px 16px;
}

.grid-head {
  background: #f8fafc;
  border-bottom: 1px solid var(--border);
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
}

.grid-row {
  border-bottom: 1px solid var(--border-light);
  font-size: 13px;
  color: var(--text);
}

.grid-row:hover {
  background: var(--primary-bg);
}

.c-col {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.cell-name {
  border: none;
  background: transparent;
  padding: 0;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--text);
  cursor: pointer;
  min-width: 0;
}

.cell-name span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.caret {
  color: var(--text-muted);
  transition: transform var(--transition);
  flex-shrink: 0;
}

.caret.open {
  transform: rotate(90deg);
}

.c-comment {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  color: var(--text-secondary);
}

.c-comment span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.c-semantic {
  color: var(--text-secondary);
}

.grid-row-detail {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  padding: 10px 16px 14px 42px;
  border-bottom: 1px solid var(--border-light);
  background: var(--bg);
  font-size: 12.5px;
  color: var(--text-secondary);
}

.detail-pair b {
  margin-right: 6px;
  font-weight: 600;
  color: var(--text-muted);
}

.columns-empty {
  padding: 26px;
  text-align: center;
  font-size: 13px;
  color: var(--text-muted);
}

/* ---- 数据预览：带边框容器 + 底部分页 ---- */
.preview-panel {
  padding-top: 14px;
  min-height: 120px;
}

.preview-box {
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.preview-table {
  min-height: 120px;
}

.preview-loading {
  min-height: 200px;
}

.preview-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 14px;
  border-top: 1px solid var(--border-light);
  background: var(--surface);
}

.preview-range {
  font-size: 12.5px;
  color: var(--text-secondary);
}

.preview-cost {
  margin-left: 10px;
  color: var(--text-muted);
}

.preview-panel :deep(.el-table th) {
  background: #f8fafc;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 600;
}

.preview-panel :deep(.el-table td) {
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.preview-error {
  padding: 20px;
  border: 1px solid var(--el-color-danger-light-5);
  border-radius: var(--radius);
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  font-size: 13px;
}
</style>
