<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { tripApi, memberApi } from '../api/trip.js'
import { useAuthStore } from '../stores/auth.js'
import { useNoticeStore } from '../stores/notice.js'
import { useTrip } from '../composables/useTrip.js'
import { parseActivities, money } from '../utils/format.js'
import AsyncState from '../components/AsyncState.vue'
import MemberList from '../components/MemberList.vue'
import ModalDialog from '../components/ModalDialog.vue'
import PreferenceFields from '../components/PreferenceFields.vue'
import InvitationManager from '../components/InvitationManager.vue'
import TripChat from '../components/TripChat.vue'
import landscape from '../assets/landscape.svg'
import TripNavigation from '../components/TripNavigation.vue'
import { useConfirmStore } from '../stores/confirm.js'
const confirmation = useConfirmStore()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notice = useNoticeStore()
const { tripId, trip, me, isOwner, loading, error, load } = useTrip(route)
const inviteOpen = ref(false)
const editOpen = ref(false)
const preferenceOpen = ref(false)
const busy = ref(false)
const actionError = ref('')
const leaveError = ref('')
const cancelled = ref(false)
const edit = reactive({ title: '', destination: '', travelDays: 1 })
const preference = reactive({ budgetLimit: '', travelPace: 'balanced', activities: [] })
const budgetRange = computed(() => {
  const budgets = (trip.value?.tripMembers || [])
    .map((m) => Number(m.budgetLimit))
    .filter(Number.isFinite)
  return budgets.length
    ? `${money(Math.min(...budgets))} – ${money(Math.max(...budgets))}`
    : '暂未设置'
})
function openEdit() {
  Object.assign(edit, {
    title: trip.value.title,
    destination: trip.value.destination,
    travelDays: trip.value.travelDays,
  })
  actionError.value = ''
  editOpen.value = true
}
function openPreference() {
  Object.assign(preference, {
    budgetLimit: me.value.budgetLimit,
    travelPace: me.value.travelPace,
    activities: parseActivities(me.value.favoriteActivities),
  })
  actionError.value = ''
  preferenceOpen.value = true
}
async function save(kind) {
  if (busy.value) return
  actionError.value = ''
  if (kind === 'preference' && !preference.activities.length) {
    actionError.value = '请至少选择一项活动偏好'
    return
  }
  busy.value = true
  try {
    if (kind === 'trip')
      await tripApi.update({
        id: tripId.value,
        title: edit.title.trim(),
        destination: edit.destination.trim(),
        travelDays: Number(edit.travelDays),
      })
    else
      await memberApi.update({
        tripId: tripId.value,
        userId: auth.user.id,
        budgetLimit: preference.budgetLimit,
        travelPace: preference.travelPace,
        favoriteActivities: JSON.stringify(preference.activities),
      })
    notice.show('修改已保存')
    editOpen.value = false
    preferenceOpen.value = false
    await load()
  } catch (cause) {
    actionError.value = cause.message
  } finally {
    busy.value = false
  }
}
async function leave() {
  if (
    busy.value ||
    (!cancelled.value &&
      !(await confirmation.ask(
        isOwner.value
          ? {
              title: '取消旅行并退出？',
              message:
                '你是这趟旅行的发起人。退出将把整趟旅行标记为已取消，邀请也将无法继续使用。其他伙伴仍可查看这趟已取消的旅行。',
              confirmText: '确认取消并退出',
              danger: true,
            }
          : {
              title: '离开这支旅行小队？',
              message: '退出后将无法继续参与这趟旅行，也暂时无法通过邀请重新加入。',
              confirmText: '确认退出',
              danger: true,
            },
      )))
  )
    return
  busy.value = true
  leaveError.value = ''
  try {
    if (isOwner.value && !cancelled.value) {
      await tripApi.updateStatus(tripId.value, 'cancelled')
      cancelled.value = true
    }
    await memberApi.leave(tripId.value, auth.user.id)
    notice.show(isOwner.value ? '旅行已取消，你已退出' : '已退出旅行')
    await router.push('/my-trips')
  } catch (cause) {
    leaveError.value = cancelled.value
      ? `旅行已取消，但退出尚未完成：${cause.message}`
      : isOwner.value
        ? `未能取消旅行，尚未执行退出：${cause.message}`
        : cause.message
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <div class="page-head">
    <div class="crumb"><RouterLink to="/my-trips">我的旅行</RouterLink> / 旅行房间</div>
    <div class="eyebrow">Good company, great journeys</div>
    <h1 class="h2">{{ trip?.title || '我们的旅行房间' }}</h1>
    <p class="muted">
      {{
        trip ? `${trip.destination} · ${trip.travelDays} 天` : '先约好同行的人，再慢慢商量路线。'
      }}
      <span v-if="trip">· 一起把想法变成计划</span>
    </p>
    <TripNavigation :trip-id="tripId" />
  </div>
  <AsyncState :loading="loading" :error="error" @retry="load"
    ><div v-if="trip" class="page-layout">
      <div class="stack">
        <section class="card card-pad">
          <div class="row between wrap">
            <h2 class="h3">
              旅行小队 <span class="badge">{{ trip.tripMembers.length }} 人已加入</span>
            </h2>
            <button
              v-if="isOwner && !cancelled"
              class="btn btn-line btn-sm"
              :disabled="busy"
              @click="inviteOpen = true"
            >
              ↗ 邀请朋友
            </button>
          </div>
          <div class="divider" />
          <MemberList :members="trip.tripMembers" :user-id="auth.user?.id" />
          <div class="actions-row">
            <button
              class="btn btn-soft btn-sm"
              :disabled="busy || cancelled"
              @click="openPreference"
            >
              修改我的本次偏好</button
            ><button class="text-btn tiny" @click="load">刷新成员</button
            ><button class="text-btn tiny danger-text" :disabled="busy" @click="leave">
              {{
                busy ? '处理中…' : cancelled ? '重试退出' : isOwner ? '取消并退出旅行' : '退出旅行'
              }}
            </button>
          </div>
          <p v-if="leaveError" class="err" role="alert">{{ leaveError }}</p>
        </section>
        <TripChat :trip-id="tripId" :members="trip.tripMembers" />
      </div>
      <aside class="stack side-aside">
        <section class="card card-pad">
          <div class="summary-cover"><img :src="landscape" alt="旅行目的地山水插画" /></div>
          <div class="mt">
            <span class="badge">✦ 这次的目的地</span>
            <h2 class="h2">给期待一个方向。</h2>
            <p class="muted">{{ trip.destination }} · {{ trip.travelDays }} 天</p>
          </div>
          <div class="summary-data">
            <div>
              <span>同行伙伴</span><strong>{{ trip.tripMembers.length }} 人</strong>
            </div>
            <div>
              <span>成员个人预算范围</span><strong>{{ budgetRange }}</strong>
            </div>
          </div>
          <div class="agent-bubble">
            {{
              trip.tripMembers.length === 1
                ? '旅行已经开始筹备。先邀请同行的朋友，让大家填好各自的偏好。'
                : '伙伴们的期待已在这里。准备好后，把这些想法交给 AI 整理成行程。'
            }}
          </div>
          <RouterLink v-if="!cancelled" class="btn btn-wide mt" :to="`/trips/${tripId}/itinerary`"
            >✦ 进入 AI 行程</RouterLink
          ><button
            v-if="isOwner && !cancelled"
            class="btn btn-line btn-wide mt"
            :disabled="busy"
            @click="openEdit"
          >
            修改旅行基本信息
          </button>
        </section>
      </aside>
    </div></AsyncState
  >
  <ModalDialog v-if="inviteOpen" title="邀请朋友加入" @close="inviteOpen = false"
    ><InvitationManager :trip-id="tripId"
  /></ModalDialog>
  <ModalDialog v-if="editOpen" title="修改旅行信息" @close="!busy && (editOpen = false)"
    ><form class="stack" @submit.prevent="save('trip')">
      <div>
        <label class="label" for="edit-title">旅行名称</label
        ><input id="edit-title" v-model="edit.title" class="field" required maxlength="100" />
      </div>
      <div>
        <label class="label" for="edit-city">目的地</label
        ><input id="edit-city" v-model="edit.destination" class="field" required maxlength="100" />
      </div>
      <div>
        <label class="label" for="edit-days">旅行天数</label
        ><input
          id="edit-days"
          v-model="edit.travelDays"
          type="number"
          min="1"
          max="365"
          step="1"
          class="field"
          required
        />
      </div>
      <p class="tiny muted">修改基本信息不会自动重写已有行程，可在群聊中 @AI 提出调整。</p>
      <p v-if="actionError" class="err" role="alert">{{ actionError }}</p>
      <button class="btn" :disabled="busy">{{ busy ? '保存中…' : '保存旅行信息' }}</button>
    </form></ModalDialog
  >
  <ModalDialog
    v-if="preferenceOpen"
    title="我的本次旅行偏好"
    @close="!busy && (preferenceOpen = false)"
    ><form class="stack" @submit.prevent="save('preference')">
      <div>
        <label class="label" for="member-budget">本次预算（元 / 人）</label
        ><input
          id="member-budget"
          v-model="preference.budgetLimit"
          type="number"
          min="0.01"
          step="0.01"
          class="field"
          required
        />
      </div>
      <PreferenceFields
        v-model:pace="preference.travelPace"
        v-model:activities="preference.activities"
      />
      <p v-if="actionError" class="err" role="alert">{{ actionError }}</p>
      <button class="btn" :disabled="busy">{{ busy ? '保存中…' : '保存本次偏好' }}</button>
    </form></ModalDialog
  >
</template>
