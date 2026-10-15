<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { useNoticeStore } from '../stores/notice.js'
import { useConfirmStore } from '../stores/confirm.js'
import ModalDialog from '../components/ModalDialog.vue'
const auth = useAuthStore()
const notice = useNoticeStore()
const confirmation = useConfirmStore()
const route = useRoute()
const router = useRouter()
const leaving = ref(false)
const links = computed(() => [
  { to: '/', text: '发现旅行', icon: '⌂' },
  {
    to: route.params.tripId ? `/trips/${route.params.tripId}` : '/my-trips?pick=room',
    text: '旅行房间',
    icon: '♧',
  },
  {
    to: route.params.tripId
      ? `/trips/${route.params.tripId}/itinerary`
      : '/my-trips?pick=itinerary',
    text: 'AI 行程',
    icon: '✧',
  },
  { to: '/my-trips', text: '我的旅行', icon: '▦' },
])
function isActive(link) {
  const target = router.resolve(link.to)
  return route.path === target.path && (route.query.pick || '') === (target.query.pick || '')
}
async function logout() {
  if (leaving.value) return
  leaving.value = true
  try {
    await auth.logout()
    notice.show('已退出登录')
    await router.push('/')
  } catch (error) {
    notice.show(error.message)
  } finally {
    leaving.value = false
  }
}
</script>
<template>
  <a class="skip-link" href="#main">跳到主要内容</a>
  <header class="topbar">
    <div class="shell">
      <RouterLink to="/" class="brand" aria-label="TripMate 首页"
        ><span class="brand-mark">✦</span
        ><span>Trip<span class="accent">Mate</span></span></RouterLink
      >
      <nav class="nav-links" aria-label="主导航">
        <RouterLink
          v-for="link in links"
          :key="link.text"
          :to="link.to"
          :class="{ active: isActive(link) }"
          :aria-current="isActive(link) ? 'page' : undefined"
          >{{ link.text }}</RouterLink
        >
      </nav>
      <div class="nav-end">
        <template v-if="auth.user"
          ><RouterLink class="user-name" to="/my-trips">{{
            auth.user.nickname || auth.user.userAccount
          }}</RouterLink
          ><button class="text-btn tiny" :disabled="leaving" @click="logout">
            {{ leaving ? '退出中…' : '退出' }}
          </button></template
        >
        <RouterLink
          v-else
          class="text-btn"
          :to="{ path: '/login', query: { redirect: route.fullPath } }"
          >登录 / 注册</RouterLink
        >
        <RouterLink class="btn btn-sm" to="/#create">＋ 新建旅行</RouterLink>
      </div>
    </div>
  </header>
  <main id="main" class="shell"><slot /></main>
  <footer class="footer">
    <div class="shell row between wrap">
      <span>TripMate · 让朋友的想法，变成同一场旅行</span
      ><span>先约好同行的人，再慢慢商量路线。</span>
    </div>
  </footer>
  <nav class="mobile-nav" aria-label="移动端导航">
    <RouterLink v-for="link in links" :key="link.text" :to="link.to" :class="{ active: isActive(link) }" :aria-current="isActive(link) ? 'page' : undefined"
      ><span>{{ link.icon }}</span
      >{{ link.text }}</RouterLink
    >
  </nav>
  <Transition name="toast"><div v-if="notice.message" class="toast show" role="status" aria-live="polite">
    <span aria-hidden="true">✓</span> {{ notice.message }}
  </div></Transition>
  <ModalDialog v-if="confirmation.request" :title="confirmation.request.title" @close="confirmation.answer(false)">
    <p class="confirm-description">{{ confirmation.request.message }}</p>
    <div class="confirm-actions">
      <button class="btn btn-line" autofocus @click="confirmation.answer(false)">先等等</button>
      <button class="btn" :class="{ 'btn-danger': confirmation.request.danger }" @click="confirmation.answer(true)">{{ confirmation.request.confirmText || '确认' }}</button>
    </div>
  </ModalDialog>
</template>
