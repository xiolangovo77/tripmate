import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { tripApi } from '../api/trip.js'

// 由旅行页面创建一次，成员、行程等业务只注册监听，不单独建立连接。
export function useTripEvents({ tripId, enabled, onAccessLost }) {
  const state = ref('paused')
  const active = ref(false)
  const handlers = new Map()
  const syncHandlers = new Set()
  const attached = new Set()
  let mounted = false
  let epoch = 0
  let stream
  let pollTimer
  let reconnectTimer
  let retryDelay = 3000

  function syncAll() {
    if (active.value) for (const handler of syncHandlers) handler()
  }

  function attach(eventName) {
    if (!stream || attached.has(eventName)) return
    attached.add(eventName)
    const connectionEpoch = epoch
    stream.addEventListener(eventName, () => {
      if (epoch !== connectionEpoch || !active.value) return
      for (const handler of handlers.get(eventName) || []) handler()
    })
  }

  function onEvent(eventName, handler) {
    if (!handlers.has(eventName)) handlers.set(eventName, new Set())
    handlers.get(eventName).add(handler)
    attach(eventName)
    return () => {
      const listeners = handlers.get(eventName)
      listeners?.delete(handler)
      if (!listeners?.size) handlers.delete(eventName)
    }
  }

  function onSync(handler) {
    syncHandlers.add(handler)
    return () => syncHandlers.delete(handler)
  }

  function stop() {
    epoch++
    stream?.close()
    stream = undefined
    attached.clear()
    clearInterval(pollTimer)
    clearTimeout(reconnectTimer)
    active.value = false
  }

  function revoke(message) {
    stop()
    state.value = 'paused'
    onAccessLost(message)
  }

  function handleAccessError(cause) {
    if (![401, 403, 40100, 40101, 40300, 40400].includes(cause.code)) return false
    revoke(cause.message)
    return true
  }

  function restart() {
    stop()
    if (!mounted || !enabled.value) {
      state.value = 'paused'
      return
    }
    if (navigator.onLine === false) {
      state.value = 'offline'
      return
    }
    if (document.hidden) {
      state.value = 'paused'
      return
    }
    active.value = true
    state.value = 'connecting'
    // 每个业务分别补查，某个查询失败不阻断其他模块的同步。
    syncAll()
    pollTimer = setInterval(syncAll, 30000)
    if (typeof EventSource === 'undefined') {
      state.value = 'polling'
      return
    }
    const connectionEpoch = epoch
    try {
      stream = new EventSource(tripApi.eventsUrl(tripId.value), { withCredentials: true })
      stream.onopen = () => {
        if (epoch !== connectionEpoch) return
        state.value = 'connected'
        retryDelay = 3000
        // SSE不重放遗漏事件，重连后让各业务补查快照或增量消息。
        syncAll()
      }
      for (const eventName of handlers.keys()) attach(eventName)
      stream.addEventListener('trip.access.revoked', () => {
        if (epoch === connectionEpoch) revoke('你已不在此旅行小队中')
      })
      stream.onerror = () => {
        if (epoch !== connectionEpoch) return
        state.value = 'reconnecting'
        syncAll()
        if (stream?.readyState === EventSource.CLOSED && epoch === connectionEpoch) {
          clearTimeout(reconnectTimer)
          reconnectTimer = setTimeout(restart, retryDelay)
          retryDelay = Math.min(retryDelay * 2, 30000)
        }
      }
    } catch {
      stream?.close()
      stream = undefined
      state.value = 'polling'
    }
  }

  function describe(subject) {
    return {
      connecting: `正在连接${subject}同步…`,
      connected: `${subject}实时同步中`,
      reconnecting: `实时连接恢复中，定时同步${subject}`,
      paused: `${subject}同步已暂停`,
      offline: '网络已断开，恢复后自动同步',
      polling: `正在定时同步${subject}`,
    }[state.value]
  }

  watch([tripId, enabled], restart, { flush: 'sync' })
  onMounted(() => {
    mounted = true
    document.addEventListener('visibilitychange', restart)
    window.addEventListener('online', restart)
    window.addEventListener('offline', restart)
    restart()
  })
  onBeforeUnmount(() => {
    mounted = false
    stop()
    document.removeEventListener('visibilitychange', restart)
    window.removeEventListener('online', restart)
    window.removeEventListener('offline', restart)
    handlers.clear()
    syncHandlers.clear()
  })

  return { active, state, describe, onEvent, onSync, handleAccessError }
}
