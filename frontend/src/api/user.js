import { http, requireTrue } from './http.js'
import { asId } from '../utils/json.js'
export const userApi = {
  getCurrent: () => http.get('/user/get', { skipAuthRedirect: true }),
  register: (body) => http.post('/user/register', body).then(asId),
  login: (body) => http.post('/user/login', body, { skipAuthRedirect: true }),
  logout: () => http.post('/user/logout').then(requireTrue),
  update: (body) => http.post('/user/update', body).then(requireTrue),
}
