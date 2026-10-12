<template>
  <div class="schema-explorer">
    <div class="explorer-header">
      <h4>表结构浏览</h4>
      <el-button
        :icon="Refresh"
        size="small"
        text
        :loading="loading"
        @click="handleRefresh"
      >
        刷新
      </el-button>
    </div>

    <el-tree
      v-if="treeData.length > 0"
      :data="treeData"
      :props="treeProps"
      node-key="id"
      default-expand-all
      class="schema-tree"
    >
      <template #default="{ node, data }">
        <span class="tree-node">
          <el-icon v-if="data.isTable"><Grid /></el-icon>
          <el-icon v-else><Document /></el-icon>
          <span class="node-label">{{ node.label }}</span>
          <span v-if="data.comment" class="node-comment">{{ data.comment }}</span>
        </span>
      </template>
    </el-tree>

    <el-empty v-else description="暂无数据" :image-size="60" />
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { Refresh, Grid, Document } from '@element-plus/icons-vue'
import { getTables, refreshMetadata } from '../api/endpoints'

const props = defineProps({
  datasourceId: [Number, null]
})

const tables = ref([])
const loading = ref(false)

const treeProps = {
  children: 'children',
  label: 'label'
}

const treeData = computed(() => {
  return tables.value.map(t => ({
    id: `table-${t.tableName}`,
    label: t.tableName,
    comment: t.comment || '',
    isTable: true,
    children: (t.columns || []).map(c => ({
      id: `col-${t.tableName}-${c.columnName}`,
      label: `${c.columnName} (${c.dataType})`,
      comment: c.comment || '',
      isTable: false
    }))
  }))
})

async function fetchTables() {
  if (!props.datasourceId) return
  loading.value = true
  try {
    const res = await getTables(props.datasourceId)
    tables.value = res.data || []
  } catch (e) {
    console.error('获取表结构失败:', e)
  } finally {
    loading.value = false
  }
}

async function handleRefresh() {
  if (!props.datasourceId) return
  loading.value = true
  try {
    await refreshMetadata(props.datasourceId)
    await fetchTables()
  } finally {
    loading.value = false
  }
}

watch(() => props.datasourceId, fetchTables, { immediate: true })
</script>

<style scoped>
.schema-explorer {
  padding: 12px;
}

.explorer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.explorer-header h4 {
  font-size: 14px;
  color: #303133;
}

.schema-tree {
  font-size: 13px;
}

.tree-node {
  display: flex;
  align-items: center;
  gap: 4px;
}

.node-label {
  font-weight: 500;
}

.node-comment {
  color: #909399;
  font-size: 12px;
  margin-left: 8px;
}
</style>
