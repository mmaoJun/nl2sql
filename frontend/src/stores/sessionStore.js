import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  getSessions,
  getSessionTurns,
  renameSession,
  deleteSession
} from '../api/endpoints'

const STORAGE_KEY = 'currentSessionId'

function readStoredSessionId() {
  const id = Number(localStorage.getItem(STORAGE_KEY))
  return Number.isInteger(id) && id > 0 ? id : null
}

/**
 * 会话状态。
 *
 * currentSessionId 存 localStorage，是为了刷新页面后还能回到原来那场对话。
 * 消息内容本身不缓存，一律从后端重建 —— 缓存一份就会和库里形成两份真相。
 */
export const useSessionStore = defineStore('session', () => {
  const sessions = ref([])
  const currentSessionId = ref(readStoredSessionId())
  const loading = ref(false)

  function setCurrent(id) {
    currentSessionId.value = id ?? null
    if (id == null) {
      localStorage.removeItem(STORAGE_KEY)
    } else {
      localStorage.setItem(STORAGE_KEY, String(id))
    }
  }

  /**
   * 拉会话列表。
   *
   * 失败时不抛异常：这是后台刷新，错误提示已由 client.js 的拦截器弹出，
   * 再抛出去会让 ChatPage 的 onMounted 中断在后面那一步（restoreSession），
   * 结果就是后端不可用时"加载失败 + 重试"入口反而不出现 —— 用户看到的是一片空白。
   * 列表内容保持原样不清空：一次网络抖动不该把用户眼前的会话列表抹掉。
   *
   * 与 rename/remove/loadTurns 不同，那几个的调用方需要知道成败，照常抛。
   */
  async function fetchSessions() {
    loading.value = true
    try {
      const res = await getSessions()
      sessions.value = res.data || []
    } catch (error) {
      console.error('获取会话列表失败:', error)
    } finally {
      loading.value = false
    }
  }

  async function rename(id, title) {
    await renameSession(id, { title })
    await fetchSessions()
  }

  async function remove(id) {
    await deleteSession(id)
    sessions.value = sessions.value.filter((s) => s.id !== id)
    if (currentSessionId.value === id) {
      setCurrent(null)
    }
  }

  async function loadTurns(id) {
    const res = await getSessionTurns(id)
    return res.data || []
  }

  return {
    sessions,
    currentSessionId,
    loading,
    fetchSessions,
    rename,
    remove,
    loadTurns,
    setCurrent
  }
})
