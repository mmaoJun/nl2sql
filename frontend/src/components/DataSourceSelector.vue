<template>
  <div class="ds-selector">
    <el-select
      :model-value="modelValue"
      placeholder="选择数据源"
      @update:model-value="$emit('update:modelValue', $event)"
      :teleported="false"
    >
      <template #prefix>
        <el-icon :size="16"><Coin /></el-icon>
      </template>
      <el-option
        v-for="ds in dataSources"
        :key="ds.id"
        :label="ds.name"
        :value="ds.id"
      >
        <div class="ds-option">
          <span class="ds-name">{{ ds.name }}</span>
          <span class="ds-type">{{ ds.type }}</span>
        </div>
      </el-option>
    </el-select>
  </div>
</template>

<script setup>
defineProps({
  modelValue: [Number, null],
  dataSources: {
    type: Array,
    default: () => []
  }
})

defineEmits(['update:modelValue'])
</script>

<style scoped>
.ds-selector {
  min-width: 200px;
}

.ds-selector :deep(.el-select) {
  width: 100%;
}

.ds-selector :deep(.el-input__wrapper) {
  border-radius: var(--radius);
  box-shadow: none;
  border: 1px solid var(--border);
  padding-left: 8px;
  transition: all var(--transition);
}

.ds-selector :deep(.el-input__wrapper:hover) {
  border-color: var(--primary-light);
}

.ds-selector :deep(.el-input__wrapper.is-focus) {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(79, 70, 229, 0.08);
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
</style>
