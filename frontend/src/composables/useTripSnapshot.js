import { computed, onBeforeUnmount, ref, watch } from 'vue'

// 共享查询生命周期；各业务独立维护数据、错误和待刷新标记。
export function useTripSnapshot({
  tripId,
  events,
  eventName,
  read,
  apply,
  reset = () => {},
  paused = ref(false),
}) {
  const ready = ref(false)
  const refreshing = ref(false)
  const error = ref('')
  const loading = computed(() => !ready.value && !error.value)
  let epoch = 0
  let controller
  let pending
  let dirty = false

  function cancel() {
    epoch++
    controller?.abort()
    controller = undefined
    pending = undefined
    refreshing.value = false
    dirty = true
  }

  function refresh({ force = false } = {}) {
    dirty = true
    if (!events.active.value || (paused.value && !force)) return Promise.resolve(false)
    if (pending) return pending
    const requestEpoch = epoch
    const id = tripId.value
    if (!ready.value) error.value = ''
    refreshing.value = true
    pending = (async () => {
      do {
        dirty = false
        controller = new AbortController()
        try {
          const data = await read(id, controller.signal)
          if (epoch !== requestEpoch || tripId.value !== id) return false
          apply(data)
          ready.value = true
          error.value = ''
        } catch (cause) {
          if (epoch !== requestEpoch) return false
          if (events.handleAccessError(cause)) return false
          error.value = cause.message
          dirty = false
          return false
        }
        // 查询期间收到的通知不能丢弃，当前查询结束后再补查。
      } while (dirty && events.active.value && (!paused.value || force))
      return true
    })().finally(() => {
      if (epoch === requestEpoch) {
        pending = undefined
        controller = undefined
        refreshing.value = false
      }
    })
    return pending
  }

  const unsubscribe = events.onEvent(eventName, refresh)
  const stopSync = events.onSync(refresh)
  watch(
    [tripId, events.active],
    ([id, active], [previousId]) => {
      if (!active || id !== previousId) cancel()
      if (id !== previousId) {
        ready.value = false
        error.value = ''
        reset()
      }
    },
    { flush: 'sync' },
  )
  watch(
    paused,
    (value) => {
      if (value) cancel()
      else if (dirty) refresh()
    },
    { flush: 'sync' },
  )
  onBeforeUnmount(() => {
    cancel()
    unsubscribe()
    stopSync()
  })

  return { ready, loading, refreshing, error, refresh }
}
