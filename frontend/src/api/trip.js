import { http, requireTrue } from './http.js'
import { asId } from '../utils/json.js'
export const isEmptyTrips = (error) =>
  (error.code === 40000 && error.message === '该用户不存在旅行信息') ||
  (error.code === 40400 && error.message === '不存在旅行信息')
export const tripApi = {
  create: (body) => http.post('/trip/add', body),
  get: (tripId) => http.get('/trip/get', { params: { tripId } }),
  update: (body) => http.post('/trip/update', body).then(requireTrue),
  updateStatus: (id, status) => http.post('/trip/updateStatus', { id, status }).then(requireTrue),
  prompt: (tripId) => http.get('/trip/getPrompt', { params: { tripId } }),
  async page(body, config) {
    try {
      return await http.post('/trip/page', body, config)
    } catch (error) {
      if (isEmptyTrips(error)) return []
      throw error
    }
  },
}
export const memberApi = {
  join: ({ tripId, inviteCode, budgetLimit }) =>
    http.post('/tripMember/add', { tripId, inviteCode, budgetLimit }).then(asId),
  update: (body) => http.post('/tripMember/update', body).then(requireTrue),
  leave: (tripId, userId) =>
    http.post('/tripMember/updateStatus', { tripId, userId, status: 'left' }).then(requireTrue),
}
