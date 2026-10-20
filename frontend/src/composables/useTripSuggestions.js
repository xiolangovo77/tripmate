import { computed, onMounted, ref } from 'vue'
import { messageApi } from '../api/message.js'
import { asId } from '../utils/json.js'
import { useTripSnapshot } from './useTripSnapshot.js'

export function useTripSuggestions({ tripId, status, events, paused }) {
  const items = ref([])
  const selected = ref([])
  const snapshot = useTripSnapshot({
    tripId,
    events,
    eventName: 'trip.suggestions.changed',
    queryKey: status,
    paused,
    read: (id, signal) => messageApi.suggestions(id, status.value, { signal }),
    apply(data) {
      items.value = data.map((item) => ({ ...item, id: asId(item.id) }))
      const available = new Set(items.value.map((item) => item.id))
      selected.value = selected.value.filter((id) => available.has(id))
    },
    reset() {
      items.value = []
      selected.value = []
    },
  })
  // 建议组件可能在共享连接启动后才挂载，需要主动读取首份快照。
  onMounted(() => snapshot.refresh())

  return {
    items,
    selected,
    loading: snapshot.loading,
    refreshing: snapshot.refreshing,
    error: computed(() => (snapshot.ready.value ? '' : snapshot.error.value)),
    syncError: computed(() => (snapshot.ready.value ? snapshot.error.value : '')),
    syncStatus: computed(() => events.describe('AI 建议')),
    load: () => snapshot.refresh({ force: true }),
  }
}
