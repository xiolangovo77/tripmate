import { computed, ref } from 'vue'
import { itineraryApi } from '../api/itinerary.js'
import { asId } from '../utils/json.js'
import { useTripSnapshot } from './useTripSnapshot.js'

export function useTripItinerary({ tripId, trip, events, busy, day }) {
  const itinerary = ref(null)
  const itineraryId = computed(() => itinerary.value?.id || null)
  const snapshot = useTripSnapshot({
    tripId,
    events,
    eventName: 'trip.itinerary.changed',
    paused: busy,
    read: (id, signal) => itineraryApi.getByTrip(id, { signal }),
    apply(data) {
      // 新接口一次返回完整行程和当前ID，替换行程后不再访问旧ID。
      const value = data == null ? null : { ...data, id: asId(data.id) }
      itinerary.value = value
      if (trip.value) trip.value.hasItinerary = value != null
      const days = [
        ...new Set((value?.itineraryItemVOList || []).map((item) => item.dayNumber)),
      ].sort((a, b) => a - b)
      if (!days.includes(day.value)) day.value = days[0] || 1
    },
    reset() {
      itinerary.value = null
      day.value = 1
    },
  })

  return {
    itinerary,
    itineraryId,
    planLoading: snapshot.loading,
    planRefreshing: snapshot.refreshing,
    // 首次失败显示加载错误；后续失败保留已展示的行程并单独提示。
    planError: computed(() => (snapshot.ready.value ? '' : snapshot.error.value)),
    planSyncError: computed(() => (snapshot.ready.value ? snapshot.error.value : '')),
    planSyncStatus: computed(() => events.describe('行程')),
    loadPlan: () => snapshot.refresh({ force: true }),
  }
}
