import { http, requireCount } from './http.js'
export const messageApi = {
  page: (tripId, lastCreateTime) =>
    http.get('/tripMessage/page', {
      params: { tripId, pageSize: 30, ...(lastCreateTime ? { lastCreateTime } : {}) },
    }),
  add: (tripId, content) => http.post('/tripMessage/add', { tripId, content }),
  ai: (tripId, replyToId) =>
    http.post('/tripMessage/addAi', { tripId, replyToId }, { timeout: 180000 }),
  async suggestions(tripId, changeStatus) {
    try {
      return await http.post('/tripMessage/getAiByChangeStatus', { tripId, changeStatus })
    } catch (error) {
      if (error.code === 40400 && error.message === 'ai消息列表为空') return []
      throw error
    }
  },
  ignore: (tripId, ids) =>
    http
      .post('/tripMessage/updateChangeStatusByIds', { tripId, ids, changeStatus: 'ignored' })
      .then((count) => requireCount(count, ids.length)),
}
