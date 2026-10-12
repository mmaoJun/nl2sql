import client from './client'

// 认证
export const login = (data) => client.post('/auth/login', data)
export const register = (data) => client.post('/auth/register', data)

// 对话
export const chat = (data) => client.post('/chat', data)

// 元数据
export const getTables = (datasourceId) => client.get('/metadata/tables', { params: { datasourceId } })
export const getColumns = (datasourceId, tableName) => client.get(`/metadata/tables/${tableName}/columns`, { params: { datasourceId } })
export const refreshMetadata = (datasourceId) => client.post('/metadata/refresh', null, { params: { datasourceId } })

// 数据源管理
export const getDataSources = () => client.get('/datasources')
export const getSupportedTypes = () => client.get('/datasources/supported-types')
export const createDataSource = (data) => client.post('/datasources', data)
export const updateDataSource = (id, data) => client.put(`/datasources/${id}`, data)
export const deleteDataSource = (id) => client.delete(`/datasources/${id}`)
export const testDataSource = (id) => client.post(`/datasources/${id}/test`)

// 模型候选
export const getModels = () => client.get('/models')

// 会话
export const getSessions = () => client.get('/sessions')
export const createSession = (data) => client.post('/sessions', data)
export const getSessionTurns = (id) => client.get(`/sessions/${id}/turns`)
export const renameSession = (id, data) => client.patch(`/sessions/${id}`, data)
export const deleteSession = (id) => client.delete(`/sessions/${id}`)
