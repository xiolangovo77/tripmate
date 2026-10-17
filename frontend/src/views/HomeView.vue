<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { tripApi } from '../api/trip.js'
import { asId } from '../utils/json.js'
import landscape from '../assets/landscape.svg'
const auth = useAuthStore()
const router = useRouter()
const draftKey = 'tripmate.create-draft.v1'
const form = reactive({ destination: '', travelDays: 2, budgetLimit: '' })
try {
  const draft = JSON.parse(sessionStorage.getItem(draftKey) || 'null')
  if (draft && typeof draft === 'object') {
    for (const key of Object.keys(form))
      if (['string', 'number'].includes(typeof draft[key])) form[key] = draft[key]
  }
} catch {
  /* 存储不可用时仍可创建旅行 */
}
watch(form, () => {
  try {
    sessionStorage.setItem(draftKey, JSON.stringify(form))
  } catch {
    /* 不阻塞表单 */
  }
})
const busy = ref(false)
const error = ref('')
const cityInput = ref(null)
const ready = computed(
  () =>
    [form.destination.trim(), Number(form.travelDays) > 0, Number(form.budgetLimit) > 0].filter(
      Boolean,
    ).length,
)
const inspirations = [
  {
    city: '杭州',
    days: 2,
    budget: 800,
    title: '把周末交给山水',
    caption: '沿湖走走，留一点时间发呆。',
    mark: '01',
    tone: 'lake',
  },
  {
    city: '苏州',
    days: 3,
    budget: 1200,
    title: '在小巷里慢下来',
    caption: '园林、茶馆，还有没走过的路。',
    mark: '02',
    tone: 'garden',
  },
  {
    city: '南京',
    days: 2,
    budget: 900,
    title: '从一顿好吃的出发',
    caption: '找朋友，把想吃的列成清单。',
    mark: '03',
    tone: 'city',
  },
]
function useIdea(idea) {
  Object.assign(form, { destination: idea.city, travelDays: idea.days, budgetLimit: idea.budget })
  cityInput.value?.focus({ preventScroll: true })
  document
    .getElementById('create')
    ?.scrollIntoView({
      behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth',
      block: 'center',
    })
}
async function create() {
  if (busy.value) return
  if (!form.destination.trim()) {
    error.value = '先填一个想去的目的地吧'
    cityInput.value?.focus()
    return
  }
  if (!auth.user) {
    await router.push({ path: '/login', query: { redirect: '/#create' } })
    return
  }
  busy.value = true
  error.value = ''
  try {
    const id = asId(
      await tripApi.create({
        destination: form.destination.trim(),
        travelDays: Number(form.travelDays),
        budgetLimit: form.budgetLimit,
      }),
    )
    try {
      sessionStorage.removeItem(draftKey)
    } catch {
      /* 创建结果不受存储影响 */
    }
    await router.push(`/trips/${id}`)
  } catch (cause) {
    error.value = cause.message
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <section class="home-hero">
    <div class="hero-copy">
      <div class="eyebrow">Less planning. More going.</div>
      <h1 class="h1">一群人的旅行，<br /><em>一起想，一起去。</em></h1>
      <p class="lead">
        把「下次一起」变成下一站。<br />朋友说出期待，AI 整理路线，你们只管一起出发。
      </p>
      <div class="hero-links">
        <a class="btn" href="#create">计划下一场旅行 <span>↗</span></a
        ><RouterLink v-if="auth.user" class="text-btn" to="/my-trips">继续我的旅行 →</RouterLink
        ><a v-else class="text-btn" href="#how-it-works">看看怎么玩 ↓</a>
      </div>
      <div class="hero-note">
        <span class="little-line" />不必先想好所有细节，从一个目的地开始。
      </div>
    </div>
    <div class="hero-art">
      <div class="art-coordinate">TRIPMATE FIELD NOTES / 001</div>
      <div class="poster"><img :src="landscape" alt="湖泊、群山与亭子的山水旅行插画" /></div>
      <div class="art-caption">
        <span>下一站，和喜欢的人。</span><span>LET’S GO TOGETHER ↗</span>
      </div>
      <div class="travel-ticket">
        <span class="ticket-symbol">✦</span>
        <div><small>留给下一场相聚</small><strong>目的地，由我们决定。</strong></div>
        <span class="ticket-dots" aria-hidden="true">•••</span>
      </div>
    </div>
  </section>
  <section id="create" class="create-station">
    <div class="station-heading">
      <div>
        <span class="eyebrow">Start a new story</span>
        <h2 class="h3">下一站，想去哪里？</h2>
      </div>
      <span class="completion-indicator"
        >{{ ready }} / 3 项已填写 <span v-for="n in 3" :key="n" :class="{ filled: ready >= n }"
      /></span>
    </div>
    <form @submit.prevent="create">
      <fieldset class="create-fields" :disabled="busy">
        <div class="destination-field">
          <label class="label" for="city"><span>01</span> 这次想去哪里？</label
          ><input
            id="city"
            ref="cityInput"
            v-model="form.destination"
            class="field"
            placeholder="一座城市，一场新故事"
            maxlength="100"
            required
          />
        </div>
        <div>
          <label class="label" for="days"><span>02</span> 计划玩几天？</label
          ><input
            id="days"
            v-model="form.travelDays"
            class="field"
            type="number"
            min="1"
            step="1"
            max="365"
            required
          />
        </div>
        <div>
          <label class="label" for="budget"><span>03</span> 每人预算（元）</label
          ><input
            id="budget"
            v-model="form.budgetLimit"
            class="field"
            type="number"
            min="0.01"
            step="0.01"
            placeholder="例如：800"
            required
          />
        </div>
        <button class="btn create-submit" :disabled="busy">
          <span v-if="busy" class="spinner" />{{
            busy ? '正在创建旅行…' : auth.user ? '开始我们的旅行 ↗' : '登录并开始旅行 ↗'
          }}
        </button>
      </fieldset>
      <p v-if="error" class="err" role="alert">{{ error }}</p>
    </form>
    <p class="station-footnote">
      先定个大方向，进入房间后可以邀请朋友一起商量。<span v-if="!auth.user"
        >填写的内容会为你保留到登录后。</span
      >
    </p>
  </section>
  <section class="inspiration-section">
    <div class="section-heading">
      <div>
        <span class="eyebrow">A little inspiration</span>
        <h2 class="h2">没想好？从这些小念头开始。</h2>
      </div>
      <span class="tiny muted">灵感预填，可随时调整</span>
    </div>
    <div class="inspiration-grid">
      <button
        v-for="idea in inspirations"
        :key="idea.city"
        class="idea-card"
        :class="idea.tone"
        @click="useIdea(idea)"
      >
        <div class="row between">
          <span class="idea-city">{{ idea.city }}</span
          ><span class="idea-number">{{ idea.mark }} /</span>
        </div>
        <h3>{{ idea.title }}</h3>
        <p>{{ idea.caption }}</p>
        <div class="idea-bottom">
          <span>{{ idea.days }} 天 · 预算参考 ¥{{ idea.budget }} / 人</span
          ><span class="idea-arrow" aria-hidden="true">↗</span>
        </div>
      </button>
    </div>
  </section>
  <section id="how-it-works" class="how-section">
    <div>
      <span class="eyebrow">Better, together</span>
      <h2 class="h2">少一点来回纠结，<br />多一点共同期待。</h2>
      <p class="muted">从第一个想法，到大家都点头的行程。</p>
    </div>
    <div class="how-steps">
      <article>
        <span>01</span>
        <div>
          <h3>先发起，再慢慢完善</h3>
          <p>选个目的地，给这场旅行一个开始。</p>
        </div>
      </article>
      <article>
        <span>02</span>
        <div>
          <h3>让每个人的期待被看见</h3>
          <p>分享邀请，各自填写预算、节奏和喜欢的玩法。</p>
        </div>
      </article>
      <article>
        <span>03</span>
        <div>
          <h3>把讨论变成一份同行计划</h3>
          <p>AI 整理路线，一起讨论调整，由发起人确认。</p>
        </div>
      </article>
    </div>
  </section>
</template>
