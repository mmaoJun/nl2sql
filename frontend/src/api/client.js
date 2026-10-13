import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

/**
 * 未认证统一处理：清凭证 + 轻提示 + 跳登录（带原路由，登录后可回跳）。
 *
 * 后端返回 403（Spring Security 默认入口点，T10 实测；不是 401），
 * axios 拦截器与 SSE 客户端（chatStream.js，fetch 通道）共用这份逻辑。
 * 已在登录页时不重复提示/跳转（登录失败是业务码 401，不走这里）。
 */
export function handleUnauthenticated() {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  const current = router.currentRoute.value
  if (current.path !== '/login') {
    ElMessage.warning('登录已过期，请重新登录')
    router.push({ path: '/login', query: { redirect: current.fullPath } })
  }
}

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 60000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截器 - 添加 JWT token
client.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器
client.interceptors.response.use(
  (response) => {
    const data = response.data
    if (data.code && data.code !== 200) {
      // config.silent：调用方自己渲染错误态（如批量测试连接，逐个弹提示会刷屏）
      if (!response.config.silent) ElMessage.error(data.message || '请求失败')
      // 把业务码挂到 Error 上：调用方要区分"会话已不存在"（3001，该清掉本地 id 重拉列表）
      // 和网络/后端故障（该保留列表只给重试入口），靠匹配中文消息文本判断是脆的
      const error = new Error(data.message)
      error.code = data.code
      return Promise.reject(error)
    }
    return data
  },
  (error) => {
    // 后端未配置 exceptionHandling：未认证/凭证无效由 Spring Security 默认入口点返回
    // HTTP 403（不是 401，T10 实测），两个状态码都按"未登录"处理统一跳登录
    if (error.response?.status === 401 || error.response?.status === 403) {
      handleUnauthenticated()
    } else if (!error.config?.silent) {
      ElMessage.error(error.response?.data?.message || '网络错误')
    }
    return Promise.reject(error)
  }
)

export default client
