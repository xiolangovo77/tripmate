export const paceNames = {
  relaxed: '🌿 悠闲不赶路',
  balanced: '📍 适中节奏',
  packed: '🎒 行程充实',
}
export const activities = ['美食', '自然', '拍照', '文化', '购物']
export const changeNames = {
  none: '无修改建议',
  pending: '待处理',
  applied: '已应用',
  ignored: '已忽略',
}
export function parseActivities(value) {
  try {
    const result = JSON.parse(value || '[]')
    return Array.isArray(result) ? result.filter((x) => typeof x === 'string') : []
  } catch {
    return []
  }
}
export const money = (value) =>
  value == null
    ? '待定'
    : `¥${new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(value)}`
// 后端 LocalDateTime 不带时区，原样展示，避免浏览器擅自转换。
export const dateTime = (value) => (value ? String(value).replace('T', ' ').slice(0, 19) : '—')
export const sameId = (a, b) => a != null && b != null && String(a) === String(b)
export function safeRedirect(value) {
  return typeof value === 'string' &&
    value.startsWith('/') &&
    !value.startsWith('//') &&
    !value.includes('\\') &&
    !value.startsWith('/login') &&
    !value.startsWith('/register')
    ? value
    : '/my-trips'
}
export const mentionsAi = (content) => /(^|[^a-zA-Z0-9_@])@ai(?=$|[^a-zA-Z0-9_])/i.test(content)
export function newInviteCode() {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
  return Array.from(
    crypto.getRandomValues(new Uint8Array(8)),
    (n) => alphabet[n % alphabet.length],
  ).join('')
}
