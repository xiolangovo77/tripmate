import { http, requireCount } from './http.js'
export const itineraryApi = {
  idByTrip: (tripId) => http.get('/itinerary/getIdByTripId', { params: { tripId } }),
  get: (id) => http.get('/itinerary/get', { params: { id } }),
  generate: (tripId, initItineraryPrompt) =>
    http.post('/itinerary/add', { tripId, initItineraryPrompt }, { timeout: 180000 }),
  update: (id, messageList) =>
    http.put('/itinerary/update', { id, messageList }, { timeout: 180000 }),
  confirm: (id) =>
    http.put('/itinerary/confirmed', null, { params: { id } }).then((count) => requireCount(count)),
}
