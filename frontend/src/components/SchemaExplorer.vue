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
      v-if="datasourceId"
      :key="treeKey"
      lazy
      :load="loadNode"
      :props="treeProps"
      node-key="id"
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

    <el-empty v-else description="请先选择数据源" :image-size="60" />
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { Refresh, Grid, Document } from '@element-plus/icons-vue'
import { getTables, getColumns, refreshMetadata } from '../api/endpoints'

/**
 * 表结构浏览（T12 接 /api/v1/metadata）。
 *
 * 懒加载两级：根节点拉表列表（GET /metadata/tables，只回表名+注释，不含列），
 * 展开某张表时再拉该表列详情（GET /metadata/tables/{name}/columns）。
 * 刷新按钮走 POST /metadata/refresh 清后端缓存后重挂载整棵树
 * （el-tree 懒加载节点会缓存，没有公开的重置入口）。
 */
const props = defineProps({
  datasourceId: [Number, null]
})

const loading = ref(false)
const treeKey = ref(0)

const treeProps = {
  label: 'label',
  isLeaf: 'isLeaf'
}

async function loadNode(node, resolve) {
  if (!props.datasourceId) {
    resolve([])
    return
  }
  try {
    if (node.level === 0) {
      const res = await getTables(props.datasourceId)
      resolve((res.data || []).map((t) => ({
        id: `table-${t.tableName}`,
        label: t.tableName,
        comment: t.comment || '',
        isTable: true
      })))
      return
    }
    if (node.data?.isTable) {
      const res = await getColumns(props.datasourceId, node.data.label)
      resolve((res.data || []).map((c) => ({
        id: `col-${node.data.label}-${c.columnName}`,
        label: `${c.columnName} (${c.dataType})`,
        comment: c.comment || '',
        isTable: false,
        isLeaf: true
      })))
      return
    }
    resolve([])
  } catch (e) {
    // 错误提示已由 client.js 拦截器弹出；这里只保证节点能收起，不阻塞后续展开
    resolve([])
  }
}

async function handleRefresh() {
  if (!props.datasourceId) return
  loading.value = true
  try {
    await refreshMetadata(props.datasourceId)
    treeKey.value += 1
  } finally {
    loading.value = false
  }
}

// 换数据源时重挂载：不同库的表结构不同，缓存住旧节点会误导判断
watch(() => props.datasourceId, () => {
  treeKey.value += 1
})
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
