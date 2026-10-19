import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { memberApi } from '../api/trip.js'

// One connection per mounted travel page, shared by its member list and member-dependent displays.
export function useTripMembers({ tripId, trip, enabled, onAccessLost }) {
  const state = ref('connecting')
  const syncError = ref('')
  let mounted = false
  let epoch = 0
  let stream
  let pollTimer
  let debounceTimer
  let reconnectTimer
  let retryDelay = 3000
  let controller
  let pending
  let dirty = false

  const status = computed(() => {
    if (syncError.value) return '成员更新暂时失败，正在重试'
    return {
      connecting: '正在连接成员同步…',
      connected: '成员信息实时同步中',
      reconnecting: '实时连接恢复中，定时同步成员信息',
      paused: '成员同步已暂停',
      offline: '网络已断开，恢复后自动同步',
      polling: '正在定时同步成员信息',
    }[state.value]
  })

  function active() {
    return mounted && enabled.value && !document.hidden && navigator.onLine !== false
  }

  function stop() {
    epoch++
    stream?.close()
    stream = undefined
    clearInterval(pollTimer)
    clearTimeout(debounceTimer)
    clearTimeout(reconnectTimer)
    controller?.abort()
    controller = undefined
    pending = undefined
    dirty = false
  }

  function refresh() {
    if (!active()) return Promise.resolve()
    dirty = true
    if (pending) return pending
    const requestEpoch = epoch
    const id = tripId.value
    pending = (async () => {
      do {
        dirty = false
        const requestController = new AbortController()
        controller = requestController
        try {
          const members = await memberApi.list(id, { signal: requestController.signal })
          if (epoch !== requestEpoch || id !== tripId.value || !trip.value) return
          // Update only members. Open forms and all other page modules keep their local state.
          trip.value.tripMembers = members
          syncError.value = ''
        } catch (cause) {
          if (epoch !== requestEpoch) return
          if ([401, 403, 404, 40100, 40101, 40300, 40400].includes(cause.code)) {
            stop()
            onAccessLost(cause.message)
            return
          }
          syncError.value = cause.message
          // Do not loop on a failed request; the periodic retry will try again later.
          dirty = false
        }
      } while (dirty && active() && epoch === requestEpoch)
    })().finally(() => {
      if (epoch === requestEpoch) {
        pending = undefined
        controller = undefined
      }
    })
    return pending
  }

  function scheduleRefresh() {
    clearTimeout(debounceTimer)
    debounceTimer = setTimeout(refresh, 150)
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
    syncError.value = ''
    // Also repairs dropped notifications and supplies a fallback when the proxy cannot stream SSE.
    pollTimer = setInterval(refresh, 30000)
    refresh()
    if (typeof EventSource === 'undefined') {
      state.value = 'polling'
      return
    }
    state.value = 'connecting'
    const currentEpoch = epoch
    try {
      stream = new EventSource(memberApi.eventsUrl(tripId.value), { withCredentials: true })
      stream.onopen = () => {
        if (epoch !== currentEpoch) return
        state.value = 'connected'
        retryDelay = 3000
        // Every reconnect re-reads the snapshot; SSE alone does not replay missed changes.
        scheduleRefresh()
      }
      stream.addEventListener('trip.members.changed', () => {
        if (epoch === currentEpoch) scheduleRefresh()
      })
      stream.addEventListener('trip.access.revoked', () => {
        if (epoch !== currentEpoch) return
        stop()
        onAccessLost('你已不在此旅行小队中')
      })
      stream.onerror = () => {
        if (epoch !== currentEpoch) return
        state.value = 'reconnecting'
        // The authenticated snapshot can distinguish expired sessions from connection failures.
        scheduleRefresh()
        // Some HTTP failures permanently close EventSource instead of retrying automatically.
        if (stream.readyState === EventSource.CLOSED) {
          reconnectTimer = setTimeout(restart, retryDelay)
          retryDelay = Math.min(retryDelay * 2, 30000)
        }
      }
    } catch {
      state.value = 'polling'
    }
  }

  watch([tripId, enabled], restart, { flush: 'sync' })
  onMounted(() => {
    mounted = true
    document.addEventListener('visibilitychange', restart)
    window.addEventListener('online', restart)
    window.addEventListener('offline', restart)
    restart()
  })
  onUnmounted(() => {
    mounted = false
    stop()
    document.removeEventListener('visibilitychange', restart)
    window.removeEventListener('online', restart)
    window.removeEventListener('offline', restart)
  })

  return { refreshMembers: refresh, memberSyncStatus: status, memberSyncError: syncError }
}
