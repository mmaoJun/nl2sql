import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'
import { getDataSources, getSupportedTypes, testDataSource } from '../api/endpoints'

/** 连接情况文案：列表卡片、详情页头部、数据源选择器共用一套措辞 */
export const CONN_LABELS = { idle: '未测试', testing: '测试中', ok: '正常', fail: '连接失败' }

export const useDataSourceStore = defineStore('datasource', () => {
  const dataSources = ref([])
  const currentId = ref(null)
  // 后端 /datasources/supported-types 的引擎类型清单（code/displayName/defaultPort），
  // 类型制选择器与数据源管理页共用；拉取失败时留空，由消费方回退
  const supportedTypes = ref([])
  /** 连接情况：id → 'testing' | 'ok' | 'fail'，缺省表示未测试（仅内存态，刷新即清） */
  const connStatus = reactive({})

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

  /**
   * 测单个数据源的连接。
   *
   * 后端失败语义是「HTTP 200 + 业务码 500」，请求按 silent 发出（见 endpoints），
   * 不弹提示，结果只落在 connStatus 上供各处渲染。
   */
  async function checkOne(id) {
    connStatus[id] = 'testing'
    try {
      const res = await testDataSource(id)
      connStatus[id] = res.data ? 'ok' : 'fail'
    } catch (error) {
      connStatus[id] = 'fail'
    }
  }

  /**
   * 批量测连接。
   *
   * @param onlyUnknown 只测还没有结果的（打开数据源下拉时用，避免每次开面板都重测）
   * @returns 实际发起测试的个数
   */
  async function checkAll({ onlyUnknown = false } = {}) {
    const ids = dataSources.value
      .map((ds) => ds.id)
      .filter((id) => !onlyUnknown || !connStatus[id])
    if (ids.length === 0) return 0
    // 3 并发的小池子：十几个数据源同时建连接会把后端压住
    let cursor = 0
    const workers = Array.from({ length: Math.min(3, ids.length) }, async () => {
      while (cursor < ids.length) {
        await checkOne(ids[cursor++])
      }
    })
    await Promise.all(workers)
    return ids.length
  }

  function setCurrent(id) {
    currentId.value = id
  }

  const current = () => dataSources.value.find(ds => ds.id === currentId.value)

  return {
    dataSources,
    currentId,
    supportedTypes,
    connStatus,
    fetchDataSources,
    fetchSupportedTypes,
    checkOne,
    checkAll,
    setCurrent,
    current
  }
})
