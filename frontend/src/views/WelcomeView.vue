<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ProfileForm from '../components/ProfileForm.vue'
import { safeRedirect } from '../utils/format.js'
import postcard from '../assets/postcard.svg'

const route = useRoute()
const router = useRouter()
const target = router.resolve(safeRedirect(route.query.redirect))
const destination = target.path === '/welcome' ? '/my-trips' : target.fullPath
const fromInvitation = target.path === '/invitation'
const navigating = ref(false)
const error = ref('')

async function continueToDestination() {
  if (navigating.value) return
  navigating.value = true
  error.value = ''
  try {
    await router.replace(destination)
  } catch {
    error.value = '暂时无法进入下一页，请重试'
  } finally {
    navigating.value = false
  }
}
</script>

<template>
  <section class="auth-layout welcome-layout">
    <div>
      <div class="eyebrow">Make yourself at home</div>
      <h1 class="h1">让下一场旅行，<br /><em>更像你喜欢的样子。</em></h1>
      <p class="lead">
        账号已经准备好了。选一个喜欢的头像，告诉朋友怎么称呼你，再选选你的旅行偏好。
      </p>
      <p class="callout">
        {{
          fromInvitation
            ? '完善资料后继续查看朋友的邀请，再确认加入旅行。'
            : '保存后继续出发，也可以先跳过，以后再修改。'
        }}
      </p>
      <img class="auth-art" :src="postcard" alt="群山环绕湖面，一艘小船正准备出发" />
    </div>
    <section class="card card-pad">
      <ol class="welcome-steps" aria-label="注册进度">
        <li>✓ 创建账号</li>
        <li aria-current="step">2 · 完善资料</li>
      </ol>
      <h2 class="h2">个人信息与偏好</h2>
      <p class="muted">
        已为你填好默认值，可以直接继续，也可以修改。跳过会保留已保存的资料，不上传所选图片。
      </p>
      <ProfileForm
        submit-label="保存并继续"
        allow-skip
        :disabled="navigating"
        @saved="continueToDestination"
        @skip="continueToDestination"
      />
      <p v-if="error" class="err" role="alert">{{ error }}</p>
    </section>
  </section>
</template>

<style scoped>
.welcome-layout {
  align-items: start;
  gap: 48px;
}
.welcome-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  padding: 0;
  list-style: none;
  font-size: 12px;
  color: var(--muted);
}
.welcome-steps [aria-current='step'] {
  color: var(--green);
  font-weight: 700;
}
</style>
