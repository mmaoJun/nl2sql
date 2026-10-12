import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getDataSources } from '../api/endpoints'

export const useDataSourceStore = defineStore('datasource', () => {
  const dataSources = ref([])
  const currentId = ref(null)

  async function fetchDataSources() {
    try {
      const response = await getDataSources()
      dataSources.value = response.data || []
      if (dataSources.value.length > 0 && !currentId.value) {
        currentId.value = dataSources.value[0].id
      }
    } catch (error) {
      console.error('获取数据源失败:', error)
    }
  }

  function setCurrent(id) {
    currentId.value = id
  }

  const current = () => dataSources.value.find(ds => ds.id === currentId.value)

  return { dataSources, currentId, fetchDataSources, setCurrent, current }
})
