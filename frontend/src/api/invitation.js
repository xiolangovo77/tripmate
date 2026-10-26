import { http, requireTrue } from './http.js'
import { asId } from '../utils/json.js'
export const invitationApi = {
  preview: (tripId, inviteCode) => http.post('/tripInvitation/preview', { tripId, inviteCode }),
  async get(tripId) {
    try {
      return await http.get('/tripInvitation/getById', { params: { tripId } })
    } catch (error) {
      if (error.code === 40400 && error.message === '该旅行没有邀请码') return null
      throw error
    }
  },
  create: (tripId, inviteCode) =>
    http.post('/tripInvitation/add', { tripId, inviteCode }).then(asId),
  check: (tripId, inviteCode) =>
    http.post('/tripInvitation/check', { tripId, inviteCode }).then(requireTrue),
  revoke: (tripId, inviteCode) =>
    http
      .post('/tripInvitation/updateStatus', { tripId, inviteCode, status: 'revoked' })
      .then(requireTrue),
}
