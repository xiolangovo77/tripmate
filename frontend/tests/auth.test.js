import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import { userApi } from '../src/api/user.js'
import { useAuthStore } from '../src/stores/auth.js'

const currentUser = { id: '9223372036854775807', userAccount: 'session_user', nickname: '最新昵称' }
function store() {
  setActivePinia(createPinia())
  return useAuthStore()
}

test('没有浏览器缓存也通过当前用户接口恢复身份，并合并并发请求', async () => {
  let requests = 0
  userApi.getCurrent = async () => {
    requests++
    return currentUser
  }
  const auth = store()
  assert.deepEqual(await Promise.all([auth.restore(), auth.restore()]), [true, true])
  assert.equal(requests, 1)
  assert.equal(auth.user.id, currentUser.id)
})
test('会话失效清除旧身份，网络异常保持可见错误', async () => {
  const auth = store()
  auth.user = currentUser
  userApi.getCurrent = async () => {
    throw Object.assign(new Error('未登录'), { code: 40100 })
  }
  assert.equal(await auth.restore(), false)
  assert.equal(auth.user, null)
  assert.equal(auth.error, '')
  userApi.getCurrent = async () => {
    throw new Error('连接失败')
  }
  assert.equal(await auth.restore(), false)
  assert.equal(auth.error, '连接失败')
})
test('修改资料后读取后端 Session，采用服务端返回值', async () => {
  const auth = store()
  const calls = []
  userApi.update = async () => {
    calls.push('update')
    return true
  }
  userApi.getCurrent = async () => {
    calls.push('get')
    return currentUser
  }
  await auth.update({ nickname: '提交值' })
  assert.deepEqual(calls, ['update', 'get'])
  assert.equal(auth.user.nickname, '最新昵称')
})
test('保存成功但读取资料失败时明确提示，避免误报保存失败', async () => {
  const auth = store()
  userApi.update = async () => true
  userApi.getCurrent = async () => {
    throw new Error('连接失败')
  }
  await assert.rejects(auth.update({ nickname: '提交值' }), /资料已保存.*连接失败/)
})
test('退出后不接受较早发出的身份查询响应', async () => {
  const auth = store()
  let finish
  userApi.getCurrent = () =>
    new Promise((resolve) => {
      finish = resolve
    })
  const restoring = auth.restore()
  auth.clear()
  finish(currentUser)
  assert.equal(await restoring, false)
  assert.equal(auth.user, null)
})
