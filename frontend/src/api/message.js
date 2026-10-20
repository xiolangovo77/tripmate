import { http, requireCount } from './http.js'
import { asId } from '../utils/json.js'
export const messageApi = {
  page(tripId, cursor) {
    const params = { tripId, pageSize: 30 }
    if (cursor != null) {
      if (!cursor.lastCreateTime) throw new Error('消息分页游标缺少创建时间')
      params.lastCreateTime = cursor.lastCreateTime
      params.lastId = asId(cursor.lastId)
    }
    return http.get('/tripMessage/page', { params })
  },
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
