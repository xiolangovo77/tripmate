import { computed } from 'vue'
import { memberApi } from '../api/trip.js'
import { useTripSnapshot } from './useTripSnapshot.js'

export function useTripMembers({ tripId, trip, events }) {
  const snapshot = useTripSnapshot({
    tripId,
    events,
    eventName: 'trip.members.changed',
    read: (id, signal) => memberApi.list(id, { signal }),
    apply(members) {
      if (trip.value) trip.value.tripMembers = members
    },
  })
  const status = computed(() =>
    snapshot.error.value ? '成员更新暂时失败，正在重试' : events.describe('成员信息'),
  )

  return {
    refreshMembers: snapshot.refresh,
    memberSyncStatus: status,
    memberSyncError: snapshot.error,
  }
}
