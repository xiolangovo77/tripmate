<script setup>
import { scheduleLabel, routeLabel } from '../utils/tripSchedule.js'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { useNoticeStore } from '../stores/notice.js'
import { tripApi } from '../api/trip.js'
import { parseActivities, dateTime } from '../utils/format.js'
import PreferenceFields from '../components/PreferenceFields.vue'
import AsyncState from '../components/AsyncState.vue'
import ModalDialog from '../components/ModalDialog.vue'
const route = useRoute()
const auth = useAuthStore()
const notice = useNoticeStore()
const profile = reactive({
  nickname: auth.user.nickname,
  avatarUrl: auth.user.avatarUrl || '',
  travelPace: auth.user.travelPace,
  activities: parseActivities(auth.user.favoriteActivities),
})
const profileError = ref('')
const saving = ref(false)
const saved = ref(false)
const profileOpen = ref(false)
const filterOpen = ref(false)
watch(profile, () => {
  saved.value = false
})
const trips = ref([])
const loading = ref(false)
const error = ref('')
const page = ref(1)
const size = 6
const filters = reactive({
  title: '',
  destination: '',
  departureLocation: '',
  departureDate: '',
  returnDate: '',
  travelDays: '',
  status: '',
  beginTime: '',
  endTime: '',
})
const filterCount = computed(() => Object.values(filters).filter((value) => value !== '').length)
function resetFilters() {
  for (const key of Object.keys(filters)) filters[key] = ''
  load(1)
}
async function save() {
  if (saving.value) return
  profileError.value = ''
  if (!profile.activities.length) {
    profileError.value = '请至少选择一项活动偏好'
    return
  }
  saving.value = true
  try {
    await auth.update({
      userAccount: auth.user.userAccount,
      nickname: profile.nickname.trim(),
      avatarUrl: profile.avatarUrl.trim(),
      travelPace: profile.travelPace,
      favoriteActivities: JSON.stringify(profile.activities),
    })
    saved.value = true
    notice.show('个人信息和偏好已保存')
  } catch (cause) {
    profileError.value = cause.message
  } finally {
    saving.value = false
  }
}
async function load(next = 1) {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const query = { pageNum: next, pageSize: size }
    for (const [key, value] of Object.entries(filters))
      if (value !== '') query[key] = key === 'travelDays' ? Number(value) : value
    if (query.beginTime && query.endTime && query.beginTime >= query.endTime)
      throw new Error('开始时间需早于结束时间')
    trips.value = await tripApi.page(query)
    page.value = next
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}
onMounted(() => load())
</script>
<template>
  <div class="page-head dashboard-heading">
    <div>
      <div class="eyebrow">Your travel journal</div>
      <h1 class="h2">{{ auth.user?.nickname || '旅行家' }}，下一站见。</h1>
      <p class="muted">那些一起期待的旅行，都在这里。</p>
    </div>
    <RouterLink class="btn" to="/#create">＋ 新建旅行</RouterLink>
  </div>
  <div class="profile-strip">
    <span class="profile-monogram" aria-hidden="true">{{
      (auth.user?.nickname || '旅').slice(0, 1)
    }}</span>
    <div class="grow">
      <strong>按你的喜好出发</strong>
      <p class="tiny muted">
        {{
          parseActivities(auth.user?.favoriteActivities).join(' · ') ||
          '完善偏好，让下一次旅行更合心意'
        }}
      </p>
    </div>
    <button class="btn btn-line btn-sm" @click="profileOpen = true">
      编辑个人偏好 <span>↗</span>
    </button>
  </div>
  <ModalDialog v-if="profileOpen" title="个人信息与偏好" @close="!saving && (profileOpen = false)">
    <section class="card card-pad">
      <p class="tiny muted">已有旅行保留独立偏好，可在对应旅行房间修改。</p>
      <form class="stack" @submit.prevent="save">
        <div>
          <span class="label">账号</span>
          <div class="field readonly-field">{{ auth.user?.userAccount }}</div>
        </div>
        <div>
          <label class="label" for="nickname">昵称</label
          ><input id="nickname" v-model="profile.nickname" class="field" required maxlength="50" />
        </div>
        <div>
          <label class="label" for="avatar-url">头像地址（选填）</label
          ><input
            id="avatar-url"
            v-model="profile.avatarUrl"
            type="url"
            class="field"
            placeholder="https://…"
          />
        </div>
        <PreferenceFields
          v-model:pace="profile.travelPace"
          v-model:activities="profile.activities"
        />
        <p v-if="profileError" class="err" role="alert">{{ profileError }}</p>
        <button class="btn" :disabled="saving">
          {{ saving ? '保存中…' : '保存个人信息与偏好' }}
        </button>
        <p v-if="saved" class="notice">
          偏好已保存，新建或加入旅行时会自动带入。已有旅行的偏好保持不变。
        </p>
      </form>
    </section>
  </ModalDialog>
  <section class="stack trips-workspace">
    <div class="row between">
      <h2 class="h3">我的旅行 <span class="tiny muted">/ JOURNAL</span></h2>
      <button
        class="btn btn-line btn-sm"
        :aria-expanded="filterOpen"
        aria-controls="trip-filters"
        @click="filterOpen = !filterOpen"
      >
        筛选旅行 <span v-if="filterCount" class="filter-count">{{ filterCount }}</span
        ><span aria-hidden="true">{{ filterOpen ? '−' : '+' }}</span>
      </button>
    </div>
    <div v-if="route.query.pick" class="callout">
      请先选择一趟旅行，再进入{{ route.query.pick === 'itinerary' ? ' AI 行程' : '旅行房间' }}。
    </div>
    <form
      v-show="filterOpen"
      id="trip-filters"
      class="card card-pad stack filter-panel"
      @submit.prevent="load(1)"
    >
      <div class="form-grid">
        <div>
          <label class="label" for="filter-title">旅行名称</label
          ><input
            id="filter-title"
            v-model.trim="filters.title"
            class="field"
            placeholder="搜索旅行"
          />
        </div>
        <div>
          <label class="label" for="filter-destination">目的地</label
          ><input
            id="filter-destination"
            v-model.trim="filters.destination"
            class="field"
            placeholder="全部目的地"
          />
        </div>
        <div>
          <label class="label" for="filter-origin">出发地</label
          ><input
            id="filter-origin"
            v-model.trim="filters.departureLocation"
            class="field"
            placeholder="全部出发地"
          />
        </div>
        <div>
          <label class="label" for="filter-departure-date">出发日期</label
          ><input
            id="filter-departure-date"
            v-model="filters.departureDate"
            type="date"
            class="field"
          />
        </div>
        <div>
          <label class="label" for="filter-return-date">返回日期</label
          ><input id="filter-return-date" v-model="filters.returnDate" type="date" class="field" />
        </div>
        <div>
          <label class="label" for="filter-days">天数</label
          ><input
            id="filter-days"
            v-model="filters.travelDays"
            type="number"
            min="1"
            step="1"
            class="field"
            placeholder="不限"
          />
        </div>
        <div>
          <label class="label" for="filter-status">旅行状态</label
          ><select id="filter-status" v-model="filters.status" class="field">
            <option value="">全部状态</option>
            <option value="planning">筹备中</option>
            <option value="confirmed">已确认</option>
            <option value="finished">已结束</option>
            <option value="cancelled">已取消</option>
          </select>
        </div>
      </div>
      <details>
        <summary class="tiny muted">按创建时间筛选</summary>
        <div class="form-grid mt">
          <div>
            <label class="label" for="filter-begin">开始时间</label
            ><input
              id="filter-begin"
              v-model="filters.beginTime"
              type="datetime-local"
              class="field"
            />
          </div>
          <div>
            <label class="label" for="filter-end">结束时间</label
            ><input id="filter-end" v-model="filters.endTime" type="datetime-local" class="field" />
          </div>
        </div>
      </details>
      <div class="row">
        <button class="btn btn-sm" :disabled="loading">查询旅行</button
        ><button class="text-btn tiny" type="button" :disabled="loading" @click="resetFilters">
          清空筛选
        </button>
      </div>
    </form>
    <AsyncState
      :loading="loading"
      :error="error"
      :empty="!trips.length"
      empty-text="还没有符合条件的旅行，试试新的目的地吧。"
      @retry="load(page)"
      ><template #empty
        ><button v-if="filterCount" class="btn btn-line" @click="resetFilters">
          清空筛选，查看全部</button
        ><RouterLink v-else class="btn" to="/#create">发起第一场旅行 ↗</RouterLink></template
      >
      <div class="trip-card-grid">
        <article
          v-for="(trip, index) in trips"
          :key="trip.id"
          class="card trip-list-card"
          :class="`trip-tone-${index % 3}`"
        >
          <RouterLink
            class="trip-card-art"
            :to="`/trips/${trip.id}${route.query.pick === 'itinerary' ? '/itinerary' : ''}`"
            :aria-label="`继续规划${trip.title}`"
            ><span class="trip-card-destination">{{ routeLabel(trip) }}</span
            ><span class="trip-card-days">{{ trip.travelDays }}<small> DAYS</small></span
            ><span class="trip-card-path" aria-hidden="true">↗</span></RouterLink
          >
          <div class="card-pad">
            <div class="row between">
              <div>
                <h3 class="h2">{{ trip.title }}</h3>
                <p class="muted tiny">
                  {{ routeLabel(trip) }} · {{ trip.travelDays }} 天 · {{ scheduleLabel(trip) }} ·
                  创建于
                  {{ dateTime(trip.createTime) }}
                </p>
              </div>
            </div>
            <div class="actions-row">
              <RouterLink class="btn btn-sm" :to="`/trips/${trip.id}`">进入旅行房间 ↗</RouterLink
              ><RouterLink class="btn btn-line btn-sm" :to="`/trips/${trip.id}/itinerary`"
                >查看 AI 行程</RouterLink
              >
            </div>
          </div>
        </article>
      </div></AsyncState
    >
    <div v-if="trips.length || page > 1" class="row between pagination">
      <button class="btn btn-line btn-sm" :disabled="page <= 1 || loading" @click="load(page - 1)">
        上一页</button
      ><span class="tiny muted">第 {{ page }} 页</span
      ><button
        class="btn btn-line btn-sm"
        :disabled="trips.length < size || loading"
        @click="load(page + 1)"
      >
        下一页
      </button>
    </div>
  </section>
</template>
