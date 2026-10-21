import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'

export const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, top: 96 }
    return { top: 0 }
  },
  routes: [
    { path: '/', component: () => import('../views/HomeView.vue'), meta: { title: '发现旅行' } },
    { path: '/login', component: () => import('../views/AuthView.vue'), meta: { title: '登录' } },
    {
      path: '/register',
      component: () => import('../views/AuthView.vue'),
      meta: { title: '注册' },
    },
    {
      path: '/welcome',
      component: () => import('../views/WelcomeView.vue'),
      meta: { auth: true, title: '完善个人信息与偏好' },
    },
    {
      path: '/my-trips',
      component: () => import('../views/MyTripsView.vue'),
      meta: { auth: true, title: '个人中心' },
    },
    {
      path: '/trips/:tripId',
      component: () => import('../views/TripView.vue'),
      meta: { auth: true, title: '旅行房间' },
    },
    {
      path: '/trips/:tripId/itinerary',
      component: () => import('../views/ItineraryView.vue'),
      meta: { auth: true, title: 'AI 行程' },
    },
    {
      path: '/invitation',
      component: () => import('../views/InvitationView.vue'),
      meta: { title: '朋友的邀请' },
    },
    {
      path: '/:pathMatch(.*)*',
      component: () => import('../views/NotFoundView.vue'),
      meta: { title: '页面不存在' },
    },
  ],
})
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.auth && !(await auth.restore()))
    return { path: '/login', query: { redirect: to.fullPath } }
})
router.afterEach((to) => {
  document.title = `${to.meta.title} · TripMate`
})
