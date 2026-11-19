import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { guestOnly: true, title: '登录' },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { guestOnly: true, title: '注册' },
    },
    {
      path: '/chat',
      name: 'chat',
      component: () => import('@/views/ChatView.vue'),
      meta: { requiresAuth: true, title: '对话' },
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/SettingsView.vue'),
      meta: { requiresAuth: true, title: '模型设置' },
    },
    {
      path: '/profile',
      name: 'profile',
      component: () => import('@/views/ProfilePage.vue'),
      meta: { requiresAuth: true, title: '个人主页' },
    },
    {
      path: '/appearance',
      name: 'appearance',
      component: () => import('@/views/AppearanceView.vue'),
      meta: { requiresAuth: true, title: '外观' },
    },
    {
      path: '/feedback',
      name: 'feedback',
      component: () => import('@/views/FeedbackView.vue'),
      meta: { requiresAuth: true, title: '意见反馈' },
    },
    {
      path: '/trash',
      name: 'trash',
      component: () => import('@/views/TrashView.vue'),
      meta: { requiresAuth: true, title: '回收站' },
    },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  document.title = to.meta.title ? `${to.meta.title} · 拾知成图` : '拾知成图'

  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  // 已登录还去登录页，直接回对话页，避免出现「登录成功却停在登录页」的错觉
  if (to.meta.guestOnly && auth.isLoggedIn) {
    return { name: 'chat' }
  }
  return true
})

export default router
