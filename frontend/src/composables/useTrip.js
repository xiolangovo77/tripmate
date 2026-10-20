import { computed, onUnmounted, ref } from 'vue'
import { tripApi } from '../api/trip.js'
import { useAuthStore } from '../stores/auth.js'
import { sameId } from '../utils/format.js'
import { asId } from '../utils/json.js'
import { useTripMembers } from './useTripMembers.js'
import { useTripEvents } from './useTripEvents.js'
export function useTrip(route) {
  const auth = useAuthStore()
  const trip = ref(null)
  const loadedTripId = ref(null)
  const error = ref('')
  const loading = ref(true)
  const tripId = computed(() => String(route.params.tripId))
  const me = computed(() =>
    trip.value?.tripMembers?.find((member) => sameId(member.userId, auth.user?.id)),
  )
  const isOwner = computed(() => sameId(trip.value?.ownerId, auth.user?.id))
  const tripEvents = useTripEvents({
    tripId,
    enabled: computed(
      () =>
        !!trip.value &&
        loadedTripId.value === tripId.value &&
        !loading.value &&
        !error.value &&
        !!auth.user,
    ),
    onAccessLost(message) {
      error.value = message
      trip.value = null
    },
  })
  const memberSync = useTripMembers({ tripId, trip, events: tripEvents })
  let revision = 0
  onUnmounted(() => revision++)
  async function load() {
    const requestRevision = ++revision
    const id = tripId.value
    loading.value = true
    error.value = ''
    try {
      const data = await tripApi.get(asId(id))
      if (requestRevision !== revision || id !== tripId.value) return
      if (!data.tripMembers?.some((member) => sameId(member.userId, auth.user?.id)))
        throw new Error('你尚未加入此旅行，请通过好友的邀请加入。')
      trip.value = data
      loadedTripId.value = id
    } catch (cause) {
      if (requestRevision !== revision || id !== tripId.value) return
      error.value = cause.message
    } finally {
      if (requestRevision === revision && id === tripId.value) loading.value = false
    }
  }
  return { tripId, trip, me, isOwner, loading, error, load, tripEvents, ...memberSync }
}
