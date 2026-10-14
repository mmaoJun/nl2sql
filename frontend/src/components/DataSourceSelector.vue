<template>
  <!-- 自定义下拉：触发器（文字态 / chip 态）+ 面板（搜索 + 已配置数据源列表 + 底部添加/管理） -->
  <div class="ds-selector" :class="variant">
    <el-popover
      v-model:visible="visible"
      trigger="click"
      placement="bottom-start"
      :width="300"
      :show-arrow="false"
      popper-class="ds-popover"
    >
      <template #reference>
        <button type="button" class="ds-trigger">
          <span v-if="label" class="ds-trigger-label">{{ label }}</span>
          <span class="ds-trigger-value" :class="{ placeholder: !selected }">
            {{ selected ? selected.name : '选择数据源' }}
          </span>
          <el-icon :size="12" class="ds-caret"><ArrowDown /></el-icon>
        </button>
      </template>

      <div class="ds-panel">
        <div class="ds-search">
          <el-icon :size="14"><Search /></el-icon>
          <input v-model="keyword" class="ds-search-input" placeholder="搜索数据源" />
        </div>

        <div class="ds-list">
          <button
            v-for="ds in filtered"
            :key="ds.id"
            type="button"
            class="ds-item"
            :class="{ active: ds.id === modelValue }"
            @click="pick(ds)"
          >
            <svg
              v-if="brandOf(ds.type)"
              class="ds-item-logo"
              :viewBox="brandOf(ds.type).viewBox"
              fill="currentColor"
              aria-hidden="true"
            >
              <path :d="brandOf(ds.type).path" />
            </svg>
            <el-icon v-else :size="15" class="ds-item-icon"><Coin /></el-icon>
            <span class="ds-item-text">
              <span class="ds-item-name">{{ ds.name }}</span>
              <span class="ds-item-type">{{ (ds.type || '').toUpperCase() }}</span>
            </span>
            <span class="ds-item-conn" :class="dsStore.connStatus[ds.id] || 'idle'">
              {{ CONN_LABELS[dsStore.connStatus[ds.id]] || CONN_LABELS.idle }}
            </span>
            <el-icon v-if="ds.id === modelValue" :size="14" class="ds-item-check"><Check /></el-icon>
          </button>
          <div v-if="filtered.length === 0" class="ds-empty">
            {{ dataSources.length === 0 ? '还没有配置数据源' : '没有匹配的数据源' }}
          </div>
        </div>

        <div class="ds-divider"></div>
        <button type="button" class="ds-action" @click="goAdd">
          <el-icon :size="15"><Plus /></el-icon>
          <span>添加数据源</span>
        </button>
        <button type="button" class="ds-action" @click="goManage">
          <el-icon :size="15"><Operation /></el-icon>
          <span>管理数据源</span>
        </button>
      </div>
    </el-popover>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown, Search, Coin, Check, Plus, Operation } from '@element-plus/icons-vue'
import { BRAND_ICONS } from '../assets/brandIcons'
import { useDataSourceStore, CONN_LABELS } from '../stores/datasourceStore'

// 选择的是"已配置的数据源连接"（类型制下每种引擎一条，名称即连接名），
// 底部固定提供添加入口与管理入口；inline=欢迎页文字态，chip=对话态输入框胶囊
const props = defineProps({
  modelValue: { type: Number, default: null },
  dataSources: { type: Array, default: () => [] },
  variant: { type: String, default: 'inline' },
  label: { type: String, default: '' }
})

const emit = defineEmits(['update:modelValue'])
const router = useRouter()
const dsStore = useDataSourceStore()

const visible = ref(false)
const keyword = ref('')

// 面板展开时把还没测过的数据源补测一次：下拉里要直接能看到可用性，
// 但每次开面板都重测太费（一键全部测试在数据源管理页）
watch(visible, (open) => {
  if (open) dsStore.checkAll({ onlyUnknown: true })
})

const selected = computed(() => props.dataSources.find((ds) => ds.id === props.modelValue) || null)

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return props.dataSources
  return props.dataSources.filter((ds) =>
    `${ds.name || ''} ${ds.type || ''}`.toLowerCase().includes(kw)
  )
})

/** 类型对应的品牌标识；未知类型返回 null，由模板退回通用图标 */
function brandOf(code) {
  return BRAND_ICONS[code] || null
}

function pick(ds) {
  emit('update:modelValue', ds.id)
  visible.value = false
  keyword.value = ''
}

// 添加直达向导（/datasources/new），管理回列表（/datasources）
function goAdd() {
  visible.value = false
  router.push('/datasources/new')
}

function goManage() {
  visible.value = false
  router.push('/datasources')
}
</script>

<style scoped>
.ds-selector {
  min-width: 0;
}

.ds-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  padding: 6px 8px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  font-family: var(--font-sans);
  cursor: pointer;
  transition: all var(--transition);
}

.ds-trigger:hover {
  background: var(--surface-hover);
}

/* 欢迎页文字态：前缀「数据源」+ 已选名称，无图标无边框 */
.ds-trigger-label {
  color: var(--text-muted);
  flex-shrink: 0;
}

.ds-trigger-value {
  color: var(--text);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ds-trigger-value.placeholder {
  color: var(--text-muted);
  font-weight: 400;
}

.ds-caret {
  color: var(--text-muted);
  flex-shrink: 0;
}

/* 对话态 chip：输入框左下的小胶囊 */
.ds-selector.chip .ds-trigger {
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-hover);
  font-size: 12.5px;
}

.ds-selector.chip .ds-trigger:hover {
  background: var(--bg);
  border-color: var(--primary-light);
}

.ds-selector.chip .ds-trigger-value {
  max-width: 140px;
  font-weight: 400;
  color: var(--text-secondary);
}
</style>

<!-- 面板被 teleport 到 body，样式不能走 scoped -->
<style>
.ds-popover.el-popover.el-popper {
  padding: 6px !important;
  border-radius: 12px;
  border: 1px solid var(--border);
  box-shadow: 0 10px 24px rgba(0, 0, 0, 0.1);
}

.ds-popover .ds-panel {
  display: flex;
  flex-direction: column;
}

.ds-popover .ds-search {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  color: var(--text-muted);
}

.ds-popover .ds-search-input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--text);
}

.ds-popover .ds-search-input::placeholder {
  color: var(--text-muted);
}

.ds-popover .ds-list {
  max-height: 260px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.ds-popover .ds-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background var(--transition);
}

.ds-popover .ds-item:hover {
  background: var(--bg);
}

.ds-popover .ds-item-icon {
  color: var(--text-muted);
  flex-shrink: 0;
}

/* 品牌标识：面板窄，保持单色小尺寸；用 secondary 而非 muted，浅灰在 16px 下太虚 */
.ds-popover .ds-item-logo {
  width: 16px;
  height: 16px;
  color: var(--text-secondary);
  flex-shrink: 0;
}

.ds-popover .ds-item-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.ds-popover .ds-item-name {
  font-size: 13px;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ds-popover .ds-item-type {
  font-size: 11px;
  color: var(--text-muted);
}

/* 可用性贴在行的右侧：名称长度不一时也能对齐成一列，方便一眼扫过去对比
   正常绿、失败红、未测过/测试中保持灰 */
.ds-popover .ds-item-conn {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-muted);
}

.ds-popover .ds-item-conn.ok {
  color: var(--ok);
}

.ds-popover .ds-item-conn.fail {
  color: var(--error);
}

.ds-popover .ds-item-check {
  color: var(--text);
  flex-shrink: 0;
}

.ds-popover .ds-empty {
  padding: 16px 10px;
  text-align: center;
  font-size: 12.5px;
  color: var(--text-muted);
}

.ds-popover .ds-divider {
  height: 1px;
  margin: 5px 2px;
  background: var(--border-light);
}

.ds-popover .ds-action {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  font-family: var(--font-sans);
  cursor: pointer;
  transition: background var(--transition);
}

.ds-popover .ds-action:hover {
  background: var(--bg);
  color: var(--text);
}
</style>
