import { parse } from 'lossless-json'

// 在 JSON.parse 造成精度损失之前处理 Long。ID 字段一律作为字符串管理。
export function parseApiJson(text) {
  if (!text) return null
  return parse(
    text,
    (key, value) => {
      if (value != null && /(^id$|Id$)/.test(key)) return String(value)
      return value
    },
    (token) => {
      if (/^-?\d+$/.test(token) && !Number.isSafeInteger(Number(token))) return token
      return Number(token)
    },
  )
}

export function asId(value) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) throw new Error('后端 ID 精度异常')
  const id = String(value ?? '')
  if (!/^[1-9]\d*$/.test(id)) throw new Error('无效的业务 ID')
  return id
}
