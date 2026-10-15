<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { useNoticeStore } from '../stores/notice.js'
import { userApi } from '../api/user.js'
import { safeRedirect } from '../utils/format.js'
import postcard from '../assets/postcard.svg'
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notice = useNoticeStore()
// 路由按 path 重建页面；退出动画期间保持原表单类型。
const register = route.path === '/register'
const form = reactive({ userAccount: '', userPassword: '', checkPassword: '' })
const busy = ref(false)
const error = ref('')
async function submit() {
  if (busy.value) return
  error.value = ''
  if (register && form.userPassword !== form.checkPassword) {
    error.value = '两次输入的密码不一致'
    return
  }
  busy.value = true
  try {
    if (register) {
      await userApi.register(form)
      notice.show('注册成功，请登录')
      form.userPassword = ''
      form.checkPassword = ''
      await router.replace({ path: '/login', query: route.query })
    } else {
      await auth.login({ userAccount: form.userAccount, userPassword: form.userPassword })
      form.userPassword = ''
      await router.replace(safeRedirect(route.query.redirect))
    }
  } catch (cause) {
    error.value = cause.message
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <section class="auth-layout">
    <div>
      <div class="eyebrow">Good trips start with you</div>
      <h1 class="h1">下一场旅行，<br /><em>从这里开始。</em></h1>
      <p class="lead">把你的偏好告诉我们，<br />和朋友一起，找到舒服的旅行节奏。</p>
      <img class="auth-art" :src="postcard" alt="群山环绕湖面，一艘小船正准备出发" />
    </div>
    <section class="card card-pad">
      <h2 class="h2">{{ register ? '成为旅行搭子' : '欢迎回来' }}</h2>
      <p class="muted">
        {{ register ? '创建账号，开启一起出发的故事。' : '登录后，继续规划你们的下一段风景。' }}
      </p>
      <form class="stack" @submit.prevent="submit">
        <div>
          <label class="label" for="account">账号</label
          ><input
            id="account"
            v-model.trim="form.userAccount"
            class="field"
            autocomplete="username"
            minlength="4"
            maxlength="64"
            placeholder="至少 4 个字符"
            required
          />
        </div>
        <div>
          <label class="label" for="password">密码</label
          ><input
            id="password"
            v-model="form.userPassword"
            class="field"
            type="password"
            :autocomplete="register ? 'new-password' : 'current-password'"
            minlength="6"
            placeholder="至少 6 个字符"
            required
          />
        </div>
        <div v-if="register">
          <label class="label" for="confirm-password">确认密码</label
          ><input
            id="confirm-password"
            v-model="form.checkPassword"
            class="field"
            type="password"
            autocomplete="new-password"
            minlength="6"
            required
          />
        </div>
        <p v-if="error || auth.error" class="err" role="alert">{{ error || auth.error }}</p>
        <button class="btn btn-wide" :disabled="busy">
          {{ busy ? '请稍候…' : register ? '注册账号 ↗' : '登录 TripMate ↗' }}
        </button>
      </form>
      <div class="divider" />
      <p class="tiny muted">
        {{ register ? '已经有账号了？' : '还没有账号？' }}
        <RouterLink
          class="text-btn"
          :to="{ path: register ? '/login' : '/register', query: route.query }"
          >{{ register ? '去登录' : '创建账号' }}</RouterLink
        >
      </p>
    </section>
  </section>
</template>
