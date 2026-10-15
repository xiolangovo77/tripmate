import axios from 'axios'
import { parseApiJson } from '../utils/json.js'

export class ApiError extends Error {
  constructor(message, code) {
    super(message)
    this.code = code
  }
}

export const http = axios.create({
  baseURL: (import.meta.env?.VITE_API_BASE_URL || '/api').replace(/\/+$/, ''),
  withCredentials: true,
  timeout: 30000,
  transformResponse: [(data) => (typeof data === 'string' ? parseApiJson(data) : data)],
})

let onUnauthorized = () => {}
export function setUnauthorizedHandler(handler) {
  onUnauthorized = handler
}
http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (!body || typeof body.code !== 'number')
      throw new ApiError('服务器响应格式不正确，请检查 API 地址', 'FORMAT')
    if (body.code !== 0) {
      if (body.code === 40100 && !response.config.skipAuthRedirect) onUnauthorized()
      throw new ApiError(body.message || '请求失败', body.code)
    }
    return body.data
  },
  (error) => {
    const status = error.response?.status
    if (status === 401 && !error.config?.skipAuthRedirect) onUnauthorized()
    throw new ApiError(
      status === 401
        ? '登录已过期，请重新登录'
        : status === 403
          ? '没有执行此操作的权限'
          : error.code === 'ECONNABORTED'
            ? '请求超时，请先刷新查看结果，避免重复操作'
            : status
              ? `服务请求失败（HTTP ${status}）`
              : '无法连接服务，请检查网络或后端服务',
      status || error.code,
    )
  },
)

export function requireTrue(value) {
  if (value !== true) throw new ApiError('服务器未确认操作成功', 'RESULT')
  return value
}
export function requireCount(value, expected) {
  if (!(value > 0) || (expected != null && value !== expected))
    throw new ApiError('服务器未完成预期更新，请刷新核对', 'RESULT')
  return value
}
