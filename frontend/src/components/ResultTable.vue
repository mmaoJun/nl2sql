<template>
  <div v-if="result" class="result-table-wrapper">
    <div class="result-header">
      <span class="result-title">查询结果</span>
      <span class="result-count">{{ result.rows?.length || 0 }} 条记录</span>
    </div>
    <div class="table-container">
      <el-table
        :data="result.rows"
        size="small"
        max-height="400"
        style="width: 100%"
        :header-cell-style="headerStyle"
      >
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column
          v-for="col in result.columns"
          :key="col"
          :prop="col"
          :label="col"
          min-width="140"
          show-overflow-tooltip
        />
      </el-table>
    </div>
  </div>
</template>

<script setup>
defineProps({
  result: {
    type: Object,
    default: null
  }
})

const headerStyle = {
  background: '#f8fafc',
  color: '#475569',
  fontWeight: '600',
  fontSize: '12px',
  textTransform: 'uppercase',
  letterSpacing: '0.3px'
}
</script>

<style scoped>
.result-table-wrapper {
  margin: 4px 0 20px 44px;
  max-width: 75%;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}

.result-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  background: #f8fafc;
  border-bottom: 1px solid var(--border);
}

.result-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.3px;
}

.result-count {
  font-size: 12px;
  color: var(--text-muted);
  background: var(--surface);
  padding: 2px 8px;
  border-radius: 10px;
  border: 1px solid var(--border);
}

.table-container {
  overflow: auto;
}

.table-container :deep(.el-table) {
  --el-table-border-color: var(--border-light);
}

.table-container :deep(.el-table td) {
  font-family: var(--font-mono);
  font-size: 13px;
}

.table-container :deep(.el-table__row:hover > td) {
  background: var(--primary-bg) !important;
}
</style>
