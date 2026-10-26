import test from 'node:test'
import assert from 'node:assert/strict'
import { parseApiJson, asId } from '../src/utils/json.js'
import { mentionsAi, safeRedirect, newInviteCode } from '../src/utils/format.js'
import { http, setUnauthorizedHandler } from '../src/api/http.js'
import { messageApi } from '../src/api/message.js'
import { itineraryApi } from '../src/api/itinerary.js'
import { tripApi } from '../src/api/trip.js'
import { invitationApi } from '../src/api/invitation.js'
import { memberApi } from '../src/api/trip.js'
import { userApi } from '../src/api/user.js'

test('Long 数字在解析前保留精度，ID 字段统一字符串，金额和天数保持数字', () => {
  const value = parseApiJson(
    '{"code":0,"data":{"id":9223372036854775807,"ownerId":42,"tripId":9007199254740993,"travelDays":3,"budgetLimit":600.25}}',
  )
  assert.equal(value.data.id, '9223372036854775807')
  assert.equal(value.data.ownerId, '42')
  assert.equal(value.data.tripId, '9007199254740993')
  assert.equal(value.data.travelDays, 3)
  assert.equal(value.data.budgetLimit, 600.25)
  assert.equal(parseApiJson('{"code":0,"data":9223372036854775807}').data, '9223372036854775807')
  assert.throws(() => asId(9007199254740993))
})
test('只有独立 @AI 提及才触发 AI，普通文本与相似单词不触发', () => {
  for (const text of ['@AI 请省点钱', '大家好，@ai安排一下', '请帮我 @AI', '我想@AI安排自然景点'])
    assert.ok(mentionsAi(text), text)
  for (const text of ['省点钱', 'AI 帮帮忙', 'foo@ai.com', '@airport', '@AI_123'])
    assert.equal(mentionsAi(text), false, text)
})
test('登录返回地址限制为站内，邀请码遵循 Java 校验规则', () => {
  assert.equal(
    safeRedirect('/invitation?tripId=123&inviteCode=ABCD2345'),
    '/invitation?tripId=123&inviteCode=ABCD2345',
  )
  for (const input of ['//evil.example', 'https://evil.example', '/\\evil.example', '/login'])
    assert.equal(safeRedirect(input), '/my-trips')
  for (let i = 0; i < 30; i++) assert.match(newInviteCode(), /^[A-HJ-NP-Z2-9]{8}$/)
})
test('API 适配器严格遵守 HTTP 方法、Query/Body 和 AI 消息关联', async () => {
  const calls = []
  http.defaults.adapter = async (config) => {
    calls.push(config)
    return {
      data: '{"code":0,"data":9223372036854775807,"message":"ok"}',
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }
  }
  const id = await messageApi.add('9223372036854775806', '普通聊天')
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, '/tripMessage/add')
  assert.equal(id, '9223372036854775807')
  await messageApi.ai('9223372036854775806', id)
  assert.deepEqual(JSON.parse(calls[1].data), { tripId: '9223372036854775806', replyToId: id })
  await itineraryApi.update(id, [{ id, content: '真实建议', createTime: '2026-10-15T12:00:00' }])
  assert.equal(calls[2].method, 'put')
  assert.equal(JSON.parse(calls[2].data).messageList[0].id, id)
  http.defaults.adapter = async (config) => {
    calls.push(config)
    return { data: '{"code":0,"data":1}', status: 200, headers: {}, config }
  }
  await itineraryApi.confirm(id)
  assert.equal(calls[3].method, 'put')
  assert.deepEqual(calls[3].params, { id })
  assert.ok(!calls[3].data)
  await messageApi.page('123', { lastCreateTime: '2026-10-15T12:00:00', lastId: id })
  assert.deepEqual(calls[4].params, {
    tripId: '123',
    pageSize: 30,
    lastCreateTime: '2026-10-15T12:00:00',
    lastId: id,
  })
  await memberApi.join({ tripId: '123', inviteCode: 'ABCD2345', budgetLimit: '500', role: 'owner' })
  assert.deepEqual(JSON.parse(calls[5].data), {
    tripId: '123',
    inviteCode: 'ABCD2345',
    budgetLimit: '500',
  })
  await userApi.getCurrent()
  assert.equal(calls[6].method, 'get')
  assert.equal(calls[6].url, '/user/get')
  assert.equal(calls[6].skipAuthRedirect, true)
  http.defaults.adapter = async (config) => {
    calls.push(config)
    return { data: '{"code":0,"data":true}', status: 200, headers: {}, config }
  }
  await tripApi.updateStatus(id, 'confirmed')
  assert.equal(calls[7].method, 'post')
  assert.equal(calls[7].url, '/trip/updateStatus')
  assert.deepEqual(JSON.parse(calls[7].data), { id, status: 'confirmed' })
})
test('仅明确的无数据响应转换为空状态，权限和网络错误必须保留', async () => {
  let response = { code: 40000, message: '该用户不存在旅行信息' }
  http.defaults.adapter = async (config) => ({
    data: JSON.stringify(response),
    status: 200,
    headers: {},
    config,
  })
  assert.deepEqual(await tripApi.page({ pageNum: 1, pageSize: 6 }), [])
  response = { code: 40400, message: '该旅行没有邀请码' }
  assert.equal(await invitationApi.get('123'), null)
  response = { code: 40101, message: '无权限' }
  await assert.rejects(tripApi.page({ pageNum: 1, pageSize: 6 }), { code: 40101 })
  let expired = false
  setUnauthorizedHandler(() => {
    expired = true
  })
  response = { code: 40100, message: '未登录' }
  await assert.rejects(tripApi.page({ pageNum: 1, pageSize: 6 }), { code: 40100 })
  assert.ok(expired)
  response = { code: 0, data: false }
  await assert.rejects(invitationApi.check('123', 'ABCD2345'), { code: 'RESULT' })
  await assert.rejects(tripApi.updateStatus('123', 'cancelled'), { code: 'RESULT' })
})

test('邀请预览每次携带邀请码，不能以旅行详情接口替代；拒绝时保留错误', async () => {
  const calls = []
  let response = { code: 0, data: { title: '杭州同行', memberCount: 2, joined: false } }
  http.defaults.adapter = async (config) => {
    calls.push(config)
    return { data: JSON.stringify(response), status: 200, headers: {}, config }
  }
  assert.equal((await invitationApi.preview('123', 'ABCD2345')).joined, false)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, '/tripInvitation/preview')
  assert.equal(calls[0].method, 'post')
  assert.deepEqual(JSON.parse(calls[0].data), { tripId: '123', inviteCode: 'ABCD2345' })
  response = { code: 40000, message: '邀请码已撤销' }
  await assert.rejects(invitationApi.preview('123', 'ABCD2345'), {
    code: 40000,
    message: '邀请码已撤销',
  })
})
