import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { messageApi } from '../api/message.js'
import { asId } from '../utils/json.js'

const PAGE_SIZE = 30

function cursorOf(message) {
  return message ? { lastCreateTime: message.createTime, lastId: asId(message.id) } : null
}

function compareMessages(a, b) {
  const timeOrder = String(a.createTime).localeCompare(String(b.createTime))
  if (timeOrder) return timeOrder
  const left = BigInt(a.id)
  const right = BigInt(b.id)
  return left < right ? -1 : left > right ? 1 : 0
}

export function useTripMessages({ tripId, events, log }) {
  const messages = ref([])
  const loading = ref(false)
  const error = ref('')
  const hasMore = ref(false)
  let ready = false
  let newest = null
  let oldest = null
  let epoch = 0
  let controller
  let pending
  let dirty = false
  let statusesDirty = false
  let historyDirty = false

  function cancel() {
    epoch++
    controller?.abort()
    controller = undefined
    pending = undefined
    loading.value = false
    historyDirty = false
  }

  function merge(batch, mode, requestEpoch) {
    const element = log.value
    const height = element?.scrollHeight || 0
    const top = element?.scrollTop || 0
    const atBottom = !element || height - top - element.clientHeight < 80
    const byId = new Map(messages.value.map((item) => [item.id, item]))
    for (const item of batch) {
      const id = asId(item.id)
      // 历史翻页只补缺失记录，避免旧快照覆盖已经同步的状态。
      if (mode !== 'older' || !byId.has(id)) byId.set(id, { ...item, id })
    }
    messages.value = [...byId.values()].sort(compareMessages)
    nextTick(() => {
      if (epoch !== requestEpoch || !log.value) return
      if (mode === 'older') log.value.scrollTop = top + log.value.scrollHeight - height
      else if (mode === 'initial' || (mode === 'append' && atBottom))
        log.value.scrollTop = log.value.scrollHeight
    })
  }

  function refresh({ statuses = true } = {}) {
    dirty = true
    statusesDirty ||= statuses
    if (!events.active.value) return Promise.resolve(false)
    if (pending) return pending
    const requestEpoch = epoch
    const id = tripId.value
    const current = () => requestEpoch === epoch && id === tripId.value
    controller = new AbortController()
    const config = { signal: controller.signal }
    loading.value = true
    pending = (async () => {
      try {
        if (!ready) {
          // 首次只加载最近一页，后续向新、向旧的游标彼此独立。
          const batch = await messageApi.page(id, null, config)
          if (!current()) return false
          merge(batch, 'initial', requestEpoch)
          newest = cursorOf(batch[0])
          oldest = cursorOf(batch.at(-1))
          hasMore.value = batch.length === PAGE_SIZE
          ready = true
        }
        do {
          dirty = false
          let batch
          do {
            batch = await messageApi.latest(id, newest, config)
            if (!current()) return false
            if (batch.length) {
              merge(batch, 'append', requestEpoch)
              newest = cursorOf(batch.at(-1))
              // 初次是空群聊时，第一条增量消息同时建立历史边界。
              oldest ||= cursorOf(batch[0])
            }
            // 满一页继续向后读；不能直接跳到服务端最新消息，否则会漏消息。
          } while (batch.length === PAGE_SIZE)

          if (historyDirty && oldest && hasMore.value) {
            historyDirty = false
            const history = await messageApi.page(id, oldest, config)
            if (!current()) return false
            merge(history, 'older', requestEpoch)
            if (history.length) oldest = cursorOf(history.at(-1))
            hasMore.value = history.length === PAGE_SIZE
            statusesDirty = true
          }
          historyDirty = false

          if (statusesDirty) {
            statusesDirty = false
            // 创建时间游标只反映新增；旧建议的采纳/忽略状态按ID补查。
            const ids = messages.value
              .filter((item) => item.role === 'assistant' && item.changeStatus === 'pending')
              .map((item) => item.id)
            for (let offset = 0; offset < ids.length; offset += 50) {
              const updates = await messageApi.byIds(id, ids.slice(offset, offset + 50), config)
              if (!current()) return false
              merge(updates, 'update', requestEpoch)
            }
          }
        } while (dirty || statusesDirty || historyDirty)
        error.value = ''
        return true
      } catch (cause) {
        if (!current()) return false
        if (!events.handleAccessError(cause)) error.value = cause.message
        return false
      } finally {
        if (current()) {
          pending = undefined
          controller = undefined
          loading.value = false
        }
      }
    })()
    return pending
  }

  function loadOlder() {
    if (!hasMore.value || !oldest || loading.value) return Promise.resolve(false)
    historyDirty = true
    return refresh()
  }

  const stopMessages = events.onEvent('trip.messages.changed', () => refresh({ statuses: false }))
  const stopSuggestions = events.onEvent('trip.suggestions.changed', () => refresh())
  const stopSync = events.onSync(() => refresh())
  watch(
    [tripId, events.active],
    ([id, active], [previousId]) => {
      if (!active || id !== previousId) cancel()
      if (id !== previousId) {
        messages.value = []
        error.value = ''
        hasMore.value = false
        ready = false
        newest = null
        oldest = null
      }
    },
    { flush: 'sync' },
  )
  onMounted(() => refresh())
  onBeforeUnmount(() => {
    cancel()
    stopMessages()
    stopSuggestions()
    stopSync()
  })

  return {
    messages,
    loading,
    error,
    hasMore,
    refresh,
    loadOlder,
    syncStatus: computed(() => events.describe('群聊消息')),
  }
}
