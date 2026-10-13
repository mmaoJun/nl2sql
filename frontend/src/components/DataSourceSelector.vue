<template>
  <div class="ds-selector">
    <el-select
      :model-value="selectedType"
      placeholder="选择数据源类型"
      @update:model-value="handleSelect"
      :teleported="false"
    >
      <template #prefix>
        <el-icon :size="16"><Coin /></el-icon>
      </template>
      <el-option
        v-for="t in typeOptions"
        :key="t.code"
        :label="t.displayName"
        :value="t.code"
        :disabled="!t.configured"
      >
        <div class="ds-option">
          <span class="ds-name">{{ t.displayName }}</span>
          <span v-if="t.configured" class="ds-type">{{ t.code }}</span>
          <span v-else class="ds-unconfigured">未配置</span>
        </div>
      </el-option>
    </el-select>
  </div>
</template>

<script setup>
import { computed } from 'vue'

// 类型制选择：下拉列出引擎类型（mysql/postgresql/…），选中类型 = 使用该类型下
// 已配置的连接（同类型多条历史数据时取 id 最小的一条）；未配置的类型置灰。
// 对外的 v-model 仍是 datasourceId，ChatPage 及后端聊天接口无需感知类型概念。
const props = defineProps({
  modelValue: { type: Number, default: null },
  dataSources: { type: Array, default: () => [] },
  supportedTypes: { type: Array, default: () => [] }
})

const emit = defineEmits(['update:modelValue'])

// supported-types 拉取失败时回退：用已有数据源去重出类型清单，保证选择器可用
const typeOptions = computed(() => {
  const base = props.supportedTypes.length
    ? props.supportedTypes
    : [...new Set(props.dataSources.map((d) => d.type))].map((code) => ({
        code,
        displayName: code.toUpperCase()
      }))
  return base.map((t) => ({
    ...t,
    configured: props.dataSources.some((d) => d.type === t.code)
  }))
})

const selectedType = computed(() => {
  const ds = props.dataSources.find((d) => d.id === props.modelValue)
  return ds ? ds.type : null
})

function handleSelect(code) {
  const candidates = props.dataSources.filter((d) => d.type === code)
  if (candidates.length === 0) return
  const target = candidates.reduce((min, d) => (d.id < min.id ? d : min))
  emit('update:modelValue', target.id)
}
</script>

<style scoped>
.ds-selector {
  min-width: 200px;
}

.ds-selector :deep(.el-select) {
  width: 100%;
}

.ds-selector :deep(.el-input__wrapper) {
  height: 36px;
  border-radius: 10px;
  box-shadow: none;
  border: 1px solid var(--border);
  background: var(--surface);
  padding-left: 8px;
  transition: all var(--transition);
}

.ds-selector :deep(.el-input__wrapper:hover) {
  border-color: var(--primary-light);
}

.ds-selector :deep(.el-input__wrapper.is-focus) {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(0, 0, 0, 0.06);
}

.ds-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.ds-name {
  font-weight: 500;
}

.ds-type {
  font-size: 11px;
  color: var(--text-muted);
  background: var(--bg);
  padding: 1px 6px;
  border-radius: 4px;
  text-transform: uppercase;
}

.ds-unconfigured {
  font-size: 11px;
  color: var(--text-muted);
  padding: 1px 6px;
}
</style>
