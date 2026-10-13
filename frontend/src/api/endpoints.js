import client from './client'

// 认证
export const login = (data) => client.post('/auth/login', data)
export const register = (data) => client.post('/auth/register', data)

// 对话：流式走 SSE（fetch 通道，见 ./chatStream.js）；本文件只保留 axios 覆盖的常规接口

// 元数据
export const getTables = (datasourceId) => client.get('/metadata/tables', { params: { datasourceId } })
export const getColumns = (datasourceId, tableName) => client.get(`/metadata/tables/${tableName}/columns`, { params: { datasourceId } })
export const refreshMetadata = (datasourceId) => client.post('/metadata/refresh', null, { params: { datasourceId } })
// 数据预览：后端拼 SELECT * FROM <表名> LIMIT pageSize OFFSET (page-1)*pageSize，
// 另跑一次 COUNT(*) 带出整表总行数供前端分页；表名过白名单且两条 SQL 都过安全校验，只读
export const getTablePreview = (datasourceId, tableName, page = 1, pageSize = 50) =>
  client.get(`/metadata/tables/${tableName}/preview`, { params: { datasourceId, page, pageSize } })

// 数据源管理
export const getDataSources = () => client.get('/datasources')
export const getSupportedTypes = () => client.get('/datasources/supported-types')
export const createDataSource = (data) => client.post('/datasources', data)
export const updateDataSource = (id, data) => client.put(`/datasources/${id}`, data)
export const deleteDataSource = (id) => client.delete(`/datasources/${id}`)
// silent：连接失败的语义是「HTTP 200 + 业务码 500」，批量测试时逐个弹提示会刷屏，
// 改由调用方按数据源渲染状态
export const testDataSource = (id) => client.post(`/datasources/${id}/test`, null, { silent: true })

// 会话
export const getSessions = () => client.get('/sessions')
export const createSession = (data) => client.post('/sessions', data)
export const getSessionTurns = (id) => client.get(`/sessions/${id}/turns`)
export const renameSession = (id, data) => client.patch(`/sessions/${id}`, data)
export const deleteSession = (id) => client.delete(`/sessions/${id}`)
