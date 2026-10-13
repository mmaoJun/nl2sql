import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/chat'
  },
  {
    path: '/chat',
    name: 'Chat',
    component: () => import('../views/ChatPage.vue')
  },
  {
    path: '/datasources',
    name: 'DataSources',
    component: () => import('../views/DataSourcePage.vue')
  },
  {
    // 原「设置」页内容就是数据源管理，独立成 /datasources 后旧地址重定向兜底
    path: '/settings',
    redirect: '/datasources'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginPage.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  if (to.path !== '/login' && !token) {
    // 带上原路由，登录成功后回跳（T26 体验项）
    next({ path: '/login', query: { redirect: to.fullPath } })
  } else {
    next()
  }
})

export default router
