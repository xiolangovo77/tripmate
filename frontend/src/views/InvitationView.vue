<script setup>
import { scheduleLabel, routeLabel } from '../utils/tripSchedule.js'
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { invitationApi } from '../api/invitation.js'
import { memberApi, tripApi } from '../api/trip.js'
import { useAuthStore } from '../stores/auth.js'
import { useNoticeStore } from '../stores/notice.js'
import { asId } from '../utils/json.js'
import { paceNames, parseActivities, sameId } from '../utils/format.js'
import postcard from '../assets/postcard.svg'
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notice = useNoticeStore()
const form = reactive({ tripId: '', inviteCode: '' })
const checked = ref(null)
const trip = ref(null)
const loading = ref(false)
const joining = ref(false)
const error = ref('')
const budget = ref('')
const alreadyJoined = computed(() =>
  trip.value?.tripMembers?.some((member) => sameId(member.userId, auth.user?.id)),
)
const owner = computed(() =>
  trip.value?.tripMembers?.find((member) => sameId(member.userId, trip.value.ownerId)),
)
const returnTo = computed(() => '/invitation?' + new URLSearchParams(checked.value || form))
function invalidatePreview() {
  checked.value = null
  trip.value = null
  error.value = ''
}
async function check() {
  if (loading.value || joining.value) return
  loading.value = true
  error.value = ''
  trip.value = null
  checked.value = null
  try {
    const input = {
      tripId: asId(form.tripId.trim()),
      inviteCode: form.inviteCode.trim().toUpperCase(),
    }
    if (!/^[A-HJ-NP-Z2-9]{8}$/.test(input.inviteCode))
      throw new Error('请输入 8 位大写字母和数字组成的邀请码（不含 0、1、I、O）')
    await invitationApi.check(input.tripId, input.inviteCode)
    trip.value = await tripApi.get(input.tripId)
    checked.value = input
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}
async function join() {
  if (joining.value || !checked.value || !auth.user) return
  joining.value = true
  error.value = ''
  try {
    // 点击加入时重新验证，避免使用页面打开时已经过期或被撤销的验证结果。
    await invitationApi.check(checked.value.tripId, checked.value.inviteCode)
    const latest = await tripApi.get(checked.value.tripId)
    if (!latest.tripMembers.some((member) => sameId(member.userId, auth.user.id))) {
      await memberApi.join({
        tripId: checked.value.tripId,
        inviteCode: checked.value.inviteCode,
        budgetLimit: budget.value,
      })
      notice.show('加入成功，去看看旅行小队吧')
    }
    await router.push(`/trips/${checked.value.tripId}`)
  } catch (cause) {
    error.value = cause.message
  } finally {
    joining.value = false
  }
}
watch(
  () => [route.query.tripId, route.query.inviteCode],
  () => {
    form.tripId = String(route.query.tripId || '')
    form.inviteCode = String(route.query.inviteCode || '')
    if (form.tripId && form.inviteCode) check()
  },
  { immediate: true },
)
</script>
<template>
  <section class="invitation-layout">
    <div>
      <div class="eyebrow">Better with friends</div>
      <h1 class="h1">下一段风景，<br />想和<em>你一起看。</em></h1>
      <p class="lead">带上你的想法，加入朋友的旅行。<br />路线可以慢慢商量，同行的人先约好。</p>
      <div class="postcard">
        <img :src="postcard" alt="湖面、群山与一艘小船的旅行插画" />
        <div class="postcard-caption">
          <div>
            <strong>山水之间，留一点时间</strong>
            <div class="tiny muted">FOR OUR NEXT LITTLE ADVENTURE</div>
          </div>
          <span class="stamp">一起出发<br />TRIPMATE</span>
        </div>
      </div>
      <p class="quote">不必喜欢同样的玩法，<br />我们一起找到舒服的节奏。</p>
    </div>
    <section class="card card-pad">
      <div class="flow-status invitation-steps">
        <span class="current">① 查看邀请</span><span :class="{ current: checked }">② 确认加入</span
        ><span :class="{ current: alreadyJoined }">③ 一起出发</span>
      </div>
      <form class="stack" @submit.prevent="check">
        <div>
          <label class="label" for="invite-trip">旅行 ID</label
          ><input
            id="invite-trip"
            v-model="form.tripId"
            class="field"
            inputmode="numeric"
            pattern="[1-9][0-9]*"
            required
            :disabled="loading || joining"
            @input="invalidatePreview"
          />
        </div>
        <div>
          <label class="label" for="invite-code">朋友发来的邀请码</label>
          <div class="row">
            <input
              id="invite-code"
              v-model="form.inviteCode"
              class="field code-input"
              maxlength="8"
              required
              autocomplete="off"
              :disabled="loading || joining"
              @input="invalidatePreview"
            /><button class="btn btn-soft no-shrink" :disabled="loading || joining">
              {{ loading ? '验证中…' : '查看邀请' }}
            </button>
          </div>
        </div>
      </form>
      <p v-if="error" class="err" role="alert">{{ error }}</p>
      <div v-if="trip && checked" class="stack">
        <div class="divider" />
        <div class="row between wrap">
          <h2 class="h2">{{ trip.title }}</h2>
          <span class="badge">朋友的邀请</span>
        </div>
        <div class="invitation-facts">
          <div>
            <span>旅行路线</span><strong>{{ routeLabel(trip) }}</strong>
          </div>
          <div>
            <span>出行日期</span
            ><strong>{{ scheduleLabel(trip) }} · {{ trip.travelDays }} 天</strong>
          </div>
          <div>
            <span>发起人</span><strong>{{ owner?.nickname || '旅行发起人' }}</strong>
          </div>
        </div>
        <div class="callout">✓ 邀请验证通过 · {{ trip.tripMembers.length }} 位伙伴已加入</div>
        <template v-if="alreadyJoined"
          ><p>你已经在旅行小队里啦。</p>
          <RouterLink class="btn btn-wide" :to="`/trips/${checked.tripId}`"
            >进入旅行房间 ↗</RouterLink
          ></template
        ><template v-else-if="!auth.user"
          ><p class="muted tiny">先登录，才能把你的预算和偏好带给朋友。</p>
          <RouterLink class="btn btn-wide" :to="{ path: '/login', query: { redirect: returnTo } }"
            >登录并继续 ↗</RouterLink
          ><RouterLink class="text-btn" :to="{ path: '/register', query: { redirect: returnTo } }"
            >还没有账号？立即注册</RouterLink
          ></template
        >
        <form v-else class="stack" @submit.prevent="join">
          <div class="row">
            <span class="member-icon">🌿</span>
            <div>
              <strong>{{ auth.user.nickname }}</strong>
              <div class="tiny muted">当前登录账号</div>
            </div>
          </div>
          <div>
            <label class="label" for="join-budget">你的本次旅行预算（元）</label
            ><input
              id="join-budget"
              v-model="budget"
              type="number"
              min="0.01"
              step="0.01"
              class="field"
              placeholder="例如：800"
              required
            />
          </div>
          <div class="callout">
            <strong>个人旅行偏好</strong>
            <p>
              {{ paceNames[auth.user.travelPace] }} ·
              {{ parseActivities(auth.user.favoriteActivities).join(' / ') }}
            </p>
            <RouterLink class="tiny text-btn" to="/my-trips">去个人中心设置 ↗</RouterLink>
          </div>
          <button class="btn btn-wide" :disabled="joining">
            {{ joining ? '正在加入旅行…' : '确认加入这趟旅行 ↗' }}
          </button>
          <p class="note-small">加入后，可以在旅行房间修改本次预算和偏好。</p>
        </form>
      </div>
      <div v-else-if="!loading && !error" class="empty-state">
        <p class="muted">打开朋友的完整邀请链接，或填写旅行 ID 和邀请码。</p>
      </div>
    </section>
  </section>
</template>
