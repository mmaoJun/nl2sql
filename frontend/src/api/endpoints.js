import client from './client'

// 认证
export const login = (data) => client.post('/auth/login', data)
export const register = (data) => client.post('/auth/register', data)

// 对话：流式走 SSE（fetch 通道，见 ./chatStream.js）；本文件只保留 axios 覆盖的常规接口

// 元数据
export const getTables = (datasourceId) => client.get('/metadata/tables', { params: { datasourceId } })
export const getColumns = (datasourceId, tableName) => client.get(`/metadata/tables/${tableName}/columns`, { params: { datasourceId } })
export const refreshMetadata = (datasourceId) => client.post('/metadata/refresh', null, { params: { datasourceId } })
// 数据预览：后端拼 SELECT * FROM <表名> LIMIT n，表名过白名单且整条 SQL 过安全校验，只读
export const getTablePreview = (datasourceId, tableName, limit = 20) =>
  client.get(`/metadata/tables/${tableName}/preview`, { params: { datasourceId, limit } })

// 数据源管理
export const getDataSources = () => client.get('/datasources')
export const getSupportedTypes = () => client.get('/datasources/supported-types')
export const createDataSource = (data) => client.post('/datasources', data)
export const updateDataSource = (id, data) => client.put(`/datasources/${id}`, data)
export const deleteDataSource = (id) => client.delete(`/datasources/${id}`)
export const testDataSource = (id) => client.post(`/datasources/${id}/test`)

// 会话
export const getSessions = () => client.get('/sessions')
export const createSession = (data) => client.post('/sessions', data)
export const getSessionTurns = (id) => client.get(`/sessions/${id}/turns`)
export const renameSession = (id, data) => client.patch(`/sessions/${id}`, data)
export const deleteSession = (id) => client.delete(`/sessions/${id}`)
