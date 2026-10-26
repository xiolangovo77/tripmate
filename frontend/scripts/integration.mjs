// 对本地真实后端执行验收。创建独立测试账号/旅行；不删除或修改已有业务数据。
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { parseApiJson, asId } from '../src/utils/json.js'
import { newInviteCode } from '../src/utils/format.js'

const base = process.env.TEST_API_URL || 'http://localhost:8123/api'
const runAi = process.env.TEST_AI === '1'
const suffix = Date.now().toString(36)
const password = randomBytes(16).toString('hex')
const report = { date: new Date().toISOString(), base, runAi, results: [] }
function client() {
  let cookie = ''
  return async (
    path,
    { method = 'POST', body, query, allowError = false, timeout = 30000 } = {},
  ) => {
    const url = new URL(base + path)
    for (const [key, value] of Object.entries(query || {})) url.searchParams.set(key, value)
    const response = await fetch(url, {
      method,
      headers: { 'Content-Type': 'application/json', ...(cookie ? { Cookie: cookie } : {}) },
      body: body == null ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(timeout),
    })
    const setCookie = response.headers.getSetCookie()
    if (setCookie.length) cookie = setCookie.map((value) => value.split(';')[0]).join('; ')
    const result = parseApiJson(await response.text())
    if (allowError) return result
    assert.equal(result.code, 0, `${path}: ${result.code} ${result.message}`)
    return result.data
  }
}
const owner = client(),
  guest = client(),
  anon = client()
async function step(name, action) {
  try {
    const detail = await action()
    report.results.push({ name, status: 'PASS', detail })
    console.log(`PASS ${name}`)
    return detail
  } catch (error) {
    report.results.push({ name, status: 'FAIL', detail: error.message })
    console.log(`FAIL ${name}: ${error.message}`)
    throw error
  }
}
try {
  await step('匿名访问受保护接口返回 40100', async () =>
    assert.equal(
      (await anon('/trip/page', { body: { pageNum: 1, pageSize: 6 }, allowError: true })).code,
      40100,
    ),
  )
  const users = []
  for (const [index, request] of [owner, guest].entries()) {
    users.push(
      await step(`用户 ${index + 1} 注册与登录`, async () => {
        const userAccount = `fe_${suffix}_${index}`
        asId(
          await request('/user/register', {
            body: { userAccount, userPassword: password, checkPassword: password },
          }),
        )
        return await request('/user/login', { body: { userAccount, userPassword: password } })
      }),
    )
  }
  const tripId = await step('创建旅行，Long ID 无精度损失', async () =>
    asId(
      await owner('/trip/add', {
        body: {
          destination: '杭州',
          departureLocation: '上海',
          departureDate: '2027-04-01',
          returnDate: '2027-04-02',
          departurePeriod: 'morning',
          returnPeriod: 'evening',
          budgetLimit: '800',
        },
      }),
    ),
  )
  report.tripId = tripId
  await step('当前用户查询读取 Session，匿名请求被拒绝', async () => {
    assert.equal((await owner('/user/get', { method: 'GET' })).id, users[0].id)
    assert.equal((await anon('/user/get', { method: 'GET', allowError: true })).code, 40100)
  })
  await step('创建后获取真实房间和发起人', async () => {
    const trip = await owner('/trip/get', { method: 'GET', query: { tripId } })
    assert.equal(trip.ownerId, users[0].id)
    assert.equal(trip.tripMembers.length, 1)
  })
  await step('本人旅行分页与目的地查询', async () => {
    const list = await owner('/trip/page', {
      body: { pageNum: 1, pageSize: 6, destination: '杭州' },
    })
    assert.ok(list.some((trip) => trip.id === tripId))
  })
  const inviteCode = newInviteCode()
  await step('创建、读取、匿名校验邀请码', async () => {
    asId(await owner('/tripInvitation/add', { body: { tripId, inviteCode } }))
    assert.equal(
      (await owner('/tripInvitation/getById', { method: 'GET', query: { tripId } })).inviteCode,
      inviteCode,
    )
    assert.equal(await anon('/tripInvitation/check', { body: { tripId, inviteCode } }), true)
  })
  await step('有效邀请只公开摘要，匿名和未加入账号无法读取私人旅行信息', async () => {
    const preview = await anon('/tripInvitation/preview', { body: { tripId, inviteCode } })
    assert.equal(preview.memberCount, 1)
    assert.equal(preview.joined, false)
    for (const field of [
      'tripMembers',
      'ownerId',
      'budgetLimit',
      'favoriteActivities',
      'itineraryItemVOList',
    ])
      assert.ok(!Object.hasOwn(preview, field))
    for (const path of [
      '/trip/get',
      '/trip/getPrompt',
      '/itinerary/getIdByTripId',
      '/itinerary/getByTripId',
    ]) {
      assert.equal(
        (await anon(path, { method: 'GET', query: { tripId }, allowError: true })).code,
        40100,
      )
      assert.equal(
        (await guest(path, { method: 'GET', query: { tripId }, allowError: true })).code,
        40101,
      )
    }
    assert.equal(
      (
        await guest('/tripInvitation/getById', {
          method: 'GET',
          query: { tripId },
          allowError: true,
        })
      ).code,
      40101,
    )
  })
  await step('加入接口拒绝缺少或错误的邀请码', async () => {
    for (const extra of [{}, { inviteCode: 'INVALID' }]) {
      const response = await guest('/tripMember/add', {
        body: { tripId, budgetLimit: '650', ...extra },
        allowError: true,
      })
      assert.notEqual(response.code, 0)
      assert.match(response.message, /邀请码/)
    }
  })
  await step('第二位用户验证邀请并加入旅行', async () => {
    await guest('/tripInvitation/check', { body: { tripId, inviteCode } })
    asId(
      await guest('/tripMember/add', {
        body: { tripId, inviteCode, budgetLimit: '650' },
      }),
    )
    const trip = await guest('/trip/get', { method: 'GET', query: { tripId } })
    assert.equal(trip.tripMembers.length, 2)
    assert.equal(
      (await guest('/tripInvitation/preview', { body: { tripId, inviteCode } })).joined,
      true,
    )
  })
  await step('个人信息与本次旅行偏好更新', async () => {
    assert.equal(
      await guest('/user/update', {
        body: {
          userAccount: users[1].userAccount,
          nickname: '前端验收搭子',
          avatarUrl: '',
          travelPace: 'relaxed',
          favoriteActivities: '["自然","文化"]',
        },
      }),
      true,
    )
    assert.equal(
      await guest('/tripMember/update', {
        body: {
          tripId,
          userId: users[1].id,
          budgetLimit: '700',
          travelPace: 'relaxed',
          favoriteActivities: '["自然","文化"]',
        },
      }),
      true,
    )
    const trip = await guest('/trip/get', { method: 'GET', query: { tripId } })
    assert.equal(trip.tripMembers.find((m) => m.userId === users[1].id).travelPace, 'relaxed')
  })
  await step('修改资料无需重新登录，当前用户与新旅行使用更新后的偏好', async () => {
    const current = await guest('/user/get', { method: 'GET' })
    assert.equal(current.nickname, '前端验收搭子')
    assert.equal(current.travelPace, 'relaxed')
    const createdId = asId(
      await guest('/trip/add', {
        body: {
          destination: '苏州',
          departureLocation: '上海',
          departureDate: '2027-04-01',
          returnDate: '2027-04-01',
          departurePeriod: 'morning',
          returnPeriod: 'evening',
          budgetLimit: '400',
        },
      }),
    )
    const created = await guest('/trip/get', { method: 'GET', query: { tripId: createdId } })
    assert.equal(created.tripMembers[0].nickname, '前端验收搭子')
    assert.equal(created.tripMembers[0].travelPace, 'relaxed')
  })
  await step('普通成员无法修改旅行或查询待处理建议', async () => {
    assert.equal(
      (
        await guest('/trip/update', {
          body: {
            id: tripId,
            title: '不应修改',
            destination: '杭州',
            departureLocation: '上海',
            departureDate: '2027-04-01',
            returnDate: '2027-04-02',
            departurePeriod: 'morning',
            returnPeriod: 'evening',
          },
          allowError: true,
        })
      ).code,
      40101,
    )
    assert.equal(
      (
        await guest('/tripMessage/getAiByChangeStatus', {
          body: { tripId, changeStatus: 'pending' },
          allowError: true,
        })
      ).code,
      40101,
    )
  })
  await step('普通消息保存和历史查询，不产生 AI 回复', async () => {
    const id = asId(
      await guest('/tripMessage/add', {
        body: { tripId, content: '普通群聊测试：周末一起出发。' },
      }),
    )
    const messages = await owner('/tripMessage/page', {
      method: 'GET',
      query: { tripId, pageSize: 30 },
    })
    assert.ok(messages.some((m) => m.id === id))
    assert.equal((await anon('/tripMessage/get', { query: { id }, allowError: true })).code, 40100)
    assert.equal(await owner('/tripMessage/get', { query: { id } }), '普通群聊测试：周末一起出发。')
    assert.equal(messages.filter((m) => m.role === 'assistant').length, 0)
  })
  await step('获取尚不存在的行程 ID 返回 null', async () =>
    assert.equal(
      await owner('/itinerary/getIdByTripId', { method: 'GET', query: { tripId } }),
      null,
    ),
  )
  if (runAi) {
    let itineraryId
    await step('真实 AI 生成并获取行程', async () => {
      const initItineraryPrompt = await owner('/trip/getPrompt', {
        method: 'GET',
        query: { tripId },
      })
      itineraryId = asId(
        await owner('/itinerary/add', { body: { tripId, initItineraryPrompt }, timeout: 180000 }),
      )
      assert.equal(
        await owner('/trip/updateStatus', { body: { id: tripId, status: 'confirmed' } }),
        true,
      )
      const plan = await owner('/itinerary/get', { method: 'GET', query: { id: itineraryId } })
      assert.ok(plan.itineraryItemVOList.length)
      assert.equal(plan.tripId, tripId)
    })
    await step('@AI 消息先落库，再按 replyToId 获取真实回复', async () => {
      const replyToId = asId(
        await guest('/tripMessage/add', {
          body: {
            tripId,
            content:
              '@AI 请修改现有行程：第二天下午增加一个免费的公园散步活动，减少购物时间。请作为行程修改建议。',
          },
        }),
      )
      const reply = await guest('/tripMessage/addAi', {
        body: { tripId, replyToId },
        timeout: 180000,
      })
      assert.ok(reply)
      const list = await owner('/tripMessage/page', {
        method: 'GET',
        query: { tripId, pageSize: 30 },
      })
      assert.ok(list.some((m) => m.role === 'assistant' && m.replyToId === replyToId))
    })
    await step('采纳 AI 建议，使用返回的新行程 ID', async () => {
      const messageList = await owner('/tripMessage/getAiByChangeStatus', {
        body: { tripId, changeStatus: 'pending' },
      })
      assert.ok(messageList.length)
      const oldId = itineraryId
      itineraryId = asId(
        await owner('/itinerary/update', {
          method: 'PUT',
          body: { id: itineraryId, messageList },
          timeout: 180000,
        }),
      )
      assert.notEqual(itineraryId, oldId)
      assert.equal(
        await owner('/itinerary/getIdByTripId', { method: 'GET', query: { tripId } }),
        itineraryId,
      )
      const applied = await owner('/tripMessage/getAiByChangeStatus', {
        body: { tripId, changeStatus: 'applied' },
      })
      assert.ok(applied.some((m) => m.id === messageList[0].id))
    })
    await step('发起人确认行程并从后端读回 confirmed', async () => {
      assert.ok(await owner('/itinerary/confirmed', { method: 'PUT', query: { id: itineraryId } }))
      assert.equal(
        (await owner('/itinerary/get', { method: 'GET', query: { id: itineraryId } })).status,
        'confirmed',
      )
    })
  } else
    report.results.push({
      name: '真实 AI 生成、回复、更新、确认',
      status: 'SKIP',
      detail: '设置 TEST_AI=1 启用（会调用后端配置的大模型）',
    })
  await step('撤销邀请码后验证失败', async () => {
    await owner('/tripInvitation/updateStatus', { body: { tripId, inviteCode, status: 'revoked' } })
    assert.notEqual(
      (await anon('/tripInvitation/check', { body: { tripId, inviteCode }, allowError: true }))
        .code,
      0,
    )
  })
  await step('普通成员不能确认或取消旅行', async () => {
    for (const status of ['confirmed', 'cancelled']) {
      assert.equal(
        (await guest('/trip/updateStatus', { body: { id: tripId, status }, allowError: true }))
          .code,
        40101,
      )
    }
  })
  await step('发起人确认旅行状态，可通过已确认筛选读回', async () => {
    assert.equal(
      await owner('/trip/updateStatus', { body: { id: tripId, status: 'confirmed' } }),
      true,
    )
    const trips = await guest('/trip/page', {
      body: { pageNum: 1, pageSize: 6, status: 'confirmed' },
    })
    assert.ok(trips.some((trip) => trip.id === tripId))
  })
  await step('发起人取消并退出，其他成员可查到已取消旅行', async () => {
    assert.equal(
      await owner('/trip/updateStatus', { body: { id: tripId, status: 'cancelled' } }),
      true,
    )
    assert.equal(
      await owner('/tripMember/updateStatus', {
        body: { tripId, userId: users[0].id, status: 'left' },
      }),
      true,
    )
    const trips = await guest('/trip/page', {
      body: { pageNum: 1, pageSize: 6, status: 'cancelled' },
    })
    assert.ok(trips.some((trip) => trip.id === tripId))
    const room = await guest('/trip/get', { method: 'GET', query: { tripId } })
    assert.ok(!room.tripMembers.some((member) => member.userId === users[0].id))
    assert.ok(room.tripMembers.some((member) => member.userId === users[1].id))
    for (const path of [
      '/trip/get',
      '/trip/getPrompt',
      '/itinerary/getByTripId',
      '/tripMessage/page',
    ]) {
      assert.equal(
        (await owner(path, { method: 'GET', query: { tripId }, allowError: true })).code,
        40101,
      )
    }
  })
  await step('退出登录后原 Session 无法查询旅行', async () => {
    assert.equal(await guest('/user/logout'), true)
    assert.equal(
      (await guest('/trip/page', { body: { pageNum: 1, pageSize: 6 }, allowError: true })).code,
      40100,
    )
    await owner('/user/logout')
  })
} catch {
  process.exitCode = 1
} finally {
  await mkdir('test-results', { recursive: true })
  await writeFile('test-results/integration.json', JSON.stringify(report, null, 2))
}
