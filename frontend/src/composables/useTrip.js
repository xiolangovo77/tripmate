import { computed, ref } from 'vue'
import { tripApi } from '../api/trip.js'
import { useAuthStore } from '../stores/auth.js'
import { sameId } from '../utils/format.js'
import { asId } from '../utils/json.js'
export function useTrip(route) {
  const auth = useAuthStore()
  const trip = ref(null)
  const error = ref('')
  const loading = ref(true)
  const tripId = computed(() => String(route.params.tripId))
  const me = computed(() =>
    trip.value?.tripMembers?.find((member) => sameId(member.userId, auth.user?.id)),
  )
  const isOwner = computed(() => sameId(trip.value?.ownerId, auth.user?.id))
  async function load() {
    loading.value = true
    error.value = ''
    try {
      const data = await tripApi.get(asId(tripId.value))
      if (!data.tripMembers?.some((member) => sameId(member.userId, auth.user?.id)))
        throw new Error('你尚未加入此旅行，请通过好友的邀请加入。')
      trip.value = data
    } catch (cause) {
      error.value = cause.message
    } finally {
      loading.value = false
    }
  }
  return { tripId, trip, me, isOwner, loading, error, load }
}
