<template>
  <div class="model-selector">
    <el-select
      :model-value="modelValue"
      placeholder="选择模型"
      :teleported="false"
      @update:model-value="$emit('update:modelValue', $event)"
    >
      <template #prefix>
        <el-icon :size="16"><Cpu /></el-icon>
      </template>
      <el-option
        v-for="m in models"
        :key="m.id"
        :label="m.label"
        :value="m.id"
        :disabled="!m.available"
      >
        <el-tooltip v-if="!m.available" :content="m.unavailableReason" placement="left">
          <div class="model-option is-disabled">
            <span class="model-name">{{ m.label }}</span>
            <span class="model-provider">{{ m.provider }}</span>
          </div>
        </el-tooltip>
        <div v-else class="model-option">
          <span class="model-name">{{ m.label }}</span>
          <span class="model-provider">{{ m.provider }}</span>
        </div>
      </el-option>
    </el-select>
  </div>
</template>

<script setup>
defineProps({
  modelValue: String,
  models: {
    type: Array,
    default: () => []
  }
})

defineEmits(['update:modelValue'])
</script>

<style scoped>
.model-selector {
  min-width: 210px;
}

.model-selector :deep(.el-select) {
  width: 100%;
}

.model-selector :deep(.el-input__wrapper) {
  border-radius: var(--radius);
  box-shadow: none;
  border: 1px solid var(--border);
  padding-left: 8px;
  transition: all var(--transition);
}

.model-selector :deep(.el-input__wrapper:hover) {
  border-color: var(--primary-light);
}

.model-selector :deep(.el-input__wrapper.is-focus) {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(0, 0, 0, 0.06);
}

.model-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.model-option.is-disabled {
  opacity: 0.7;
}

.model-name {
  font-weight: 500;
}

.model-provider {
  font-size: 11px;
  color: var(--text-muted);
  background: var(--bg);
  padding: 1px 6px;
  border-radius: 4px;
  text-transform: uppercase;
}
</style>
