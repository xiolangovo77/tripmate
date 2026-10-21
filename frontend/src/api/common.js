import { http } from './http.js'

export const commonApi = {
  uploadImage(file) {
    const body = new FormData()
    body.append('file', file)
    return http.post('/common/upload', body)
  },
}
