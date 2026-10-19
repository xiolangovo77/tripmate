<script setup>
import { scheduleLabel, routeLabel, dayDate } from '../utils/tripSchedule.js'
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { itineraryApi } from '../api/itinerary.js'
import { tripApi } from '../api/trip.js'
import { useTrip } from '../composables/useTrip.js'
import { useAuthStore } from '../stores/auth.js'
import { useNoticeStore } from '../stores/notice.js'
import { asId } from '../utils/json.js'
import { money } from '../utils/format.js'
import AsyncState from '../components/AsyncState.vue'
import TripChat from '../components/TripChat.vue'
import MemberList from '../components/MemberList.vue'
import SuggestionList from '../components/SuggestionList.vue'
import CopyButton from '../components/CopyButton.vue'
import TripNavigation from '../components/TripNavigation.vue'
import { useConfirmStore } from '../stores/confirm.js'
const confirmation = useConfirmStore()
const route = useRoute()
const auth = useAuthStore()
const notice = useNoticeStore()
const {
  tripId,
  trip,
  isOwner,
  loading,
  error,
  load: loadTrip,
  memberSyncStatus,
  memberSyncError,
} = useTrip(route)
const itinerary = ref(null)
const itineraryId = ref(null)
const planLoading = ref(false)
const planError = ref('')
const busy = ref(false)
const actionError = ref('')
const tripStatusError = ref('')
const tripStatusPending = ref(false)
const busyText = ref('')
const day = ref(1)
const suggestions = ref(null)
const chat = ref(null)
const days = computed(() =>
  [...new Set((itinerary.value?.itineraryItemVOList || []).map((item) => item.dayNumber))].sort(
    (a, b) => a - b,
  ),
)
const events = computed(() =>
  (itinerary.value?.itineraryItemVOList || [])
    .filter((item) => item.dayNumber === day.value)
    .sort(
      (a, b) => a.sortOrder - b.sortOrder || String(a.startTime).localeCompare(String(b.startTime)),
    ),
)
const summary = computed(() =>
  itinerary.value
    ? [
        itinerary.value.title,
        routeLabel(trip.value),
        scheduleLabel(trip.value),
        itinerary.value.summary,
        `人均预估：${money(itinerary.value.estimatedCostPerPerson)}`,
        ...days.value.flatMap((d) => [
          `第 ${d} 天 ${dayDate(trip.value?.departureDate, d)}`,
          ...itinerary.value.itineraryItemVOList
            .filter((item) => item.dayNumber === d)
            .sort((a, b) => a.sortOrder - b.sortOrder)
            .map(
              (item) =>
                `${item.startTime || ''} ${item.activityName} · ${item.locationName || ''} · ${money(item.estimatedCostPerPerson)}`,
            ),
        ]),
      ].join('\n')
    : '',
)
async function loadPlan(id) {
  planLoading.value = true
  planError.value = ''
  try {
    const value = id || (await itineraryApi.idByTrip(tripId.value))
    itineraryId.value = value == null ? null : asId(value)
    itinerary.value = itineraryId.value ? await itineraryApi.get(itineraryId.value) : null
    if (!days.value.includes(day.value)) day.value = days.value[0] || 1
  } catch (cause) {
    planError.value = cause.message
  } finally {
    planLoading.value = false
  }
}
async function load() {
  await loadTrip()
  if (!error.value) await loadPlan()
}
async function generate() {
  if (busy.value) return
  busy.value = true
  actionError.value = ''
  busyText.value = '正在综合成员偏好生成行程，通常需要一些时间…'
  try {
    // 使用服务端收集的旅行/成员提示词，不在浏览器生成行程。
    const prompt = await tripApi.prompt(tripId.value)
    const id = asId(await itineraryApi.generate(tripId.value, prompt))
    itineraryId.value = id
    tripStatusPending.value = true
    busyText.value = '行程已生成，正在确认旅行状态…'
    await syncTripStatus()
    await loadPlan(id)
  } catch (cause) {
    actionError.value = cause.message
    await loadPlan()
  } finally {
    busy.value = false
  }
}
async function syncTripStatus() {
  tripStatusError.value = ''
  try {
    await tripApi.updateStatus(tripId.value, 'confirmed')
    tripStatusPending.value = false
    notice.show('行程已生成，旅行已确认')
  } catch (cause) {
    tripStatusError.value = `行程已生成，但旅行状态尚未确认：${cause.message}`
  }
}
async function retryTripStatus() {
  if (busy.value || !tripStatusPending.value) return
  busy.value = true
  busyText.value = '正在确认旅行状态…'
  try {
    await syncTripStatus()
  } finally {
    busy.value = false
  }
}
async function apply(messages) {
  if (busy.value || !messages.length) return
  if (
    itinerary.value.status === 'confirmed' &&
    !(await confirmation.ask({
      title: '让这份行程再好一点？',
      message: '采纳建议后会生成新的行程草案，原来的确认状态将重置，需要再次确认。',
      confirmText: '生成新草案',
    }))
  )
    return
  busy.value = true
  actionError.value = ''
  busyText.value = 'AI 正在根据所选建议更新行程…'
  try {
    // 后端在事务内将建议标记为 applied，删除旧行程并返回新 ID；不能提前更改建议状态。
    const id = asId(await itineraryApi.update(itineraryId.value, messages))
    notice.show('行程已更新，请查看新的草案')
    await loadPlan(id)
    await suggestions.value?.load()
    await chat.value?.refresh()
  } catch (cause) {
    actionError.value = cause.message
    await loadPlan()
    await suggestions.value?.load()
  } finally {
    busy.value = false
  }
}
async function confirm() {
  if (
    busy.value ||
    !(await confirmation.ask({
      title: '准备按这份计划出发？',
      message: '确认后，这份行程将作为大家的出行安排。之后仍可通过 AI 建议调整，调整后需重新确认。',
      confirmText: '确认行程',
    }))
  )
    return
  busy.value = true
  actionError.value = ''
  busyText.value = '正在确认行程…'
  try {
    await itineraryApi.confirm(itineraryId.value)
    notice.show('行程已确认')
    await loadPlan()
  } catch (cause) {
    actionError.value = cause.message
    await loadPlan()
  } finally {
    busy.value = false
  }
}
function onIgnored() {
  notice.show('建议已忽略')
  chat.value?.refresh()
}
onMounted(load)
</script>
<template>
  <div class="page-head">
    <div class="crumb">
      <RouterLink to="/my-trips">我的旅行</RouterLink> /
      <RouterLink :to="`/trips/${tripId}`">旅行房间</RouterLink> / AI 行程
    </div>
    <div class="eyebrow">Make room for adventure</div>
    <h1 class="h2">{{ trip?.title || '我们的同行计划' }}</h1>
    <p class="muted">把想去的地方，串成一起走过的路。</p>
    <TripNavigation :trip-id="tripId" />
  </div>
  <AsyncState :loading="loading" :error="error" @retry="load"
    ><div v-if="trip" class="itinerary-layout">
      <section class="stack">
        <div class="trip-cover">
          <div>
            <div class="eyebrow tiny">Your next getaway</div>
            <h2>{{ trip.title }}</h2>
            <p class="muted tiny">
              {{ routeLabel(trip) }} · {{ trip.travelDays }} 天 ·
              {{ trip.tripMembers.length }} 位伙伴
            </p>
          </div>
          <p class="tiny muted">{{ scheduleLabel(trip) }}</p>
          <span class="trip-icon" aria-hidden="true">🎒</span>
        </div>
        <div v-if="busy" class="callout" role="status"><span class="spinner" /> {{ busyText }}</div>
        <p v-if="actionError" class="error-state" role="alert">{{ actionError }}</p>
        <div v-if="tripStatusError" class="error-state" role="alert">
          <p>{{ tripStatusError }}</p>
          <button class="btn btn-line btn-sm" :disabled="busy" @click="retryTripStatus">
            重试确认旅行状态
          </button>
        </div>
        <AsyncState :loading="planLoading" :error="planError" @retry="loadPlan()"
          ><section v-if="!itinerary" class="card card-pad empty-state">
            <div class="illustration">✦</div>
            <h2 class="h2">把想法变成一份行程</h2>
            <p class="muted">
              {{
                isOwner
                  ? 'AI 将参考出发地、目的地、往返日期和时段，以及所有成员的预算与偏好。生成后旅行将标记为已确认，请先邀请好同行的伙伴。'
                  : '和伙伴一起分享想法，等待发起人生成行程。'
              }}
            </p>
            <button v-if="isOwner" class="btn" :disabled="busy" @click="generate">
              {{ busy ? '生成中…' : '✦ 生成 AI 行程' }}</button
            ><button class="text-btn" :disabled="busy" @click="loadPlan()">刷新行程</button>
          </section>
          <template v-else
            ><section class="card card-pad">
              <div class="row between wrap">
                <h2 class="h3">{{ itinerary.title }}</h2>
                <span class="badge" :class="{ orange: itinerary.status !== 'confirmed' }">{{
                  itinerary.status === 'confirmed' ? '✓ 已确认' : '行程草案'
                }}</span>
              </div>
              <p class="callout pre-wrap">{{ itinerary.summary }}</p>
            </section>
            <section class="card card-pad">
              <div class="row between">
                <h2 class="h3">每天怎么玩</h2>
                <button class="text-btn tiny" :disabled="busy" @click="loadPlan()">刷新行程</button>
              </div>
              <div class="day-tabs mt" aria-label="旅行天数">
                <button
                  v-for="d in days"
                  :key="d"
                  :class="{ active: day === d }"
                  :aria-pressed="day === d"
                  @click="day = d"
                >
                  第 {{ d }} 天
                  <small v-if="trip.departureDate">{{ dayDate(trip.departureDate, d) }}</small>
                </button>
              </div>
              <Transition name="day" mode="out-in"
                ><div :key="day" class="event-list">
                  <article v-for="event in events" :key="event.id" class="event">
                    <span class="event-dot" />
                    <div class="time">
                      {{ event.startTime?.slice(0, 5) || '待定' }}<br /><span
                        v-if="event.endTime"
                        class="tiny"
                        >{{ event.endTime.slice(0, 5) }}</span
                      >
                    </div>
                    <div class="event-content">
                      <div class="row between wrap">
                        <strong>{{ event.activityName }}</strong
                        ><span class="cost">{{ money(event.estimatedCostPerPerson) }}</span>
                      </div>
                      <p>⌖ {{ event.locationName || '地点待定' }}</p>
                      <p v-if="event.address">{{ event.address }}</p>
                      <div class="tiny pre-wrap">{{ event.description }}</div>
                      <span v-if="event.activityType" class="badge mt">{{
                        event.activityType
                      }}</span>
                    </div>
                  </article>
                </div></Transition
              >
              <div v-if="!events.length" class="empty-state muted">当天暂无活动安排</div>
              <div class="divider" />
              <div class="row between">
                <div>
                  <span class="tiny muted">AI 预估费用 · 人均</span
                  ><strong class="h2 block">{{ money(itinerary.estimatedCostPerPerson) }}</strong>
                </div>
                <span class="badge">预算参考</span>
              </div>
              <div class="actions-row">
                <button
                  v-if="isOwner"
                  class="btn"
                  :disabled="busy || itinerary.status === 'confirmed'"
                  @click="confirm"
                >
                  {{ itinerary.status === 'confirmed' ? '✓ 已确认行程' : '✓ 确认这份行程' }}</button
                ><CopyButton :text="summary" label="复制行程摘要" />
              </div>
              <p v-if="!isOwner" class="tiny muted">
                行程由发起人统一确认。你可以通过 @AI 分享修改建议。
              </p>
            </section></template
          ></AsyncState
        ><SuggestionList
          v-if="isOwner"
          ref="suggestions"
          :trip-id="tripId"
          :busy="busy"
          :can-apply="!!itinerary && !planError && !planLoading"
          @apply="apply"
          @ignored="onIgnored"
        />
      </section>
      <aside class="stack">
        <TripChat
          ref="chat"
          :trip-id="tripId"
          :members="trip.tripMembers"
          @changed="suggestions?.load()"
        />
        <section class="card card-pad">
          <h2 class="h3 mb">旅行搭子们</h2>
          <MemberList :members="trip.tripMembers" :user-id="auth.user?.id" />
          <p class="tiny muted" role="status">{{ memberSyncStatus }}</p>
          <p v-if="memberSyncError" class="tiny err">{{ memberSyncError }}</p>
          <RouterLink class="btn btn-line btn-wide mt" :to="`/trips/${tripId}`"
            >← 修改我的旅行偏好</RouterLink
          >
        </section>
      </aside>
    </div></AsyncState
  >
</template>
