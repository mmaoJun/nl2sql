import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getDataSources, getSupportedTypes } from '../api/endpoints'

export const useDataSourceStore = defineStore('datasource', () => {
  const dataSources = ref([])
  const currentId = ref(null)
  // 后端 /datasources/supported-types 的引擎类型清单（code/displayName/defaultPort），
  // 类型制选择器与数据源管理页共用；拉取失败时留空，由消费方回退
  const supportedTypes = ref([])

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

  async function fetchSupportedTypes() {
    try {
      const response = await getSupportedTypes()
      supportedTypes.value = response.data || []
    } catch (error) {
      console.error('获取支持的数据源类型失败:', error)
      supportedTypes.value = []
    }
  }

  function setCurrent(id) {
    currentId.value = id
  }

  const current = () => dataSources.value.find(ds => ds.id === currentId.value)

  return {
    dataSources,
    currentId,
    supportedTypes,
    fetchDataSources,
    fetchSupportedTypes,
    setCurrent,
    current
  }
})
