import { defineStore } from 'pinia'
import { ref } from 'vue'
import { userApi } from '../api/user.js'
export const useAuthStore = defineStore('auth', () => {
  // 身份仅从真实 Session 查询，不依赖浏览器中缓存的用户快照。
  const user = ref(null)
  const error = ref('')
  let pending
  let channel
  let revision = 0
  function clear() {
    revision++
    user.value = null
    error.value = ''
  }
  function connectTabs() {
    // 清理旧版本的显示缓存；当前版本不再保存用户缓存。
    try {
      sessionStorage.removeItem('tripmate.user-display.v1')
    } catch {
      /* 禁用存储不影响登录 */
    }
    if (typeof BroadcastChannel === 'undefined' || channel) return
    channel = new BroadcastChannel('tripmate-auth')
    channel.onmessage = () => {
      clear()
      window.location.reload()
    }
  }
  async function restore() {
    if (pending) return pending
    const requestRevision = revision
    pending = (async () => {
      error.value = ''
      try {
        const current = await userApi.getCurrent()
        // 退出或切换账号后，丢弃更早发出的查询结果。
        if (requestRevision !== revision) return false
        if (!current?.id || !current?.userAccount) throw new Error('当前用户响应不完整，请重新登录')
        user.value = current
        return true
      } catch (cause) {
        if (requestRevision !== revision) return false
        user.value = null
        if (cause.code !== 40100 && cause.code !== 401) error.value = cause.message
        return false
      } finally {
        pending = null
      }
    })()
    return pending
  }
  async function login(body) {
    const result = await userApi.login(body)
    revision++
    user.value = result
    error.value = ''
    channel?.postMessage('login')
  }
  async function logout() {
    await userApi.logout()
    clear()
    channel?.postMessage('logout')
  }
  async function update(body) {
    await userApi.update(body)
    // 等待已有查询结束，再读取后端已刷新的 Session。
    if (pending) await pending
    revision++
    if (!(await restore())) {
      throw new Error(
        `资料已保存，但未能读取更新后的用户信息：${error.value || '会话已失效，请重新登录'}`,
      )
    }
    channel?.postMessage('profile-updated')
  }
  return { user, error, restore, login, logout, update, clear, connectTabs }
})
