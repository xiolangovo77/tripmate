import { http, requireCount } from './http.js'
import { asId } from '../utils/json.js'
export const itineraryApi = {
  busy: (tripId, config) => http.get('/itinerary/busy', { ...config, params: { tripId } }),
  getByTrip: (tripId, config) =>
    http.get('/itinerary/getByTripId', { ...config, params: { tripId } }),
  idByTrip: (tripId) => http.get('/itinerary/getIdByTripId', { params: { tripId } }),
  get: (id) => http.get('/itinerary/get', { params: { id } }),
  generate: (tripId, initItineraryPrompt) =>
    http.post('/itinerary/add', { tripId, initItineraryPrompt }, { timeout: 180000 }).then(asId),
  update: (id, messageList) =>
    http.put('/itinerary/update', { id, messageList }, { timeout: 180000 }).then(asId),
  confirm: (id) =>
    http.put('/itinerary/confirmed', null, { params: { id } }).then((count) => requireCount(count)),
}
