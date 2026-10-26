import { test, expect } from '@playwright/test'

const inviteUrl = '/invitation?tripId=202&inviteCode=ABCD2345'

async function fixture(page, options = {}) {
  const state = { loggedIn: false, joined: false, revoked: false, ...options }
  const calls = []
  const user = {
    id: '102',
    userAccount: 'guest',
    nickname: '小林',
    travelPace: 'balanced',
    favoriteActivities: '["自然"]',
  }
  const preview = {
    title: '杭州同行',
    departureLocation: '上海',
    destination: '杭州',
    departureDate: '2028-04-01',
    returnDate: '2028-04-02',
    departurePeriod: 'morning',
    returnPeriod: 'evening',
    travelDays: 2,
    ownerNickname: '朋友',
    memberCount: 1,
  }
  await page.addInitScript(() => {
    window.EventSource = undefined
  })
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const path = new URL(route.request().url()).pathname.replace('/api', '')
      const body = route.request().postDataJSON()
      calls.push({ path, body })
      let data
      let failure
      if (path === '/user/get') {
        if (state.loggedIn) data = user
        else failure = { code: 40100, message: '未登录' }
      } else if (path === '/user/login') {
        state.loggedIn = true
        data = user
      } else if (path === '/tripInvitation/preview') {
        expect(body).toEqual({ tripId: '202', inviteCode: 'ABCD2345' })
        if (state.revoked) failure = { code: 40000, message: '邀请码已撤销' }
        else data = { ...preview, joined: state.loggedIn && state.joined }
      } else if (path === '/tripMember/add') {
        expect(body).toEqual({ tripId: '202', inviteCode: 'ABCD2345', budgetLimit: 800 })
        state.joined = true
        data = '202'
      } else if (path === '/trip/get' || path === '/trip/202/members') {
        if (!state.loggedIn || !state.joined)
          failure = { code: 40101, message: '你已不在此旅行小队中' }
        else {
          const members = [
            {
              userId: '102',
              nickname: '小林',
              budgetLimit: 800,
              travelPace: 'balanced',
              favoriteActivities: '[]',
            },
          ]
          data =
            path === '/trip/get'
              ? { ...preview, ownerId: '101', status: 'planning', tripMembers: members }
              : members
        }
      } else if (['/tripMessage/page', '/tripMessage/latest'].includes(path)) data = []
      else throw new Error(`意外请求：${path}`)
      await route.fulfill({ json: failure || { code: 0, data } })
    },
  )
  return { state, calls }
}

test('匿名邀请页只读取有限预览，不请求旅行详情或成员数据', async ({ page }) => {
  const { calls } = await fixture(page)
  await page.goto(inviteUrl)
  await expect(page.getByRole('heading', { name: '杭州同行' })).toBeVisible()
  await expect(page.getByText('✓ 邀请验证通过 · 1 位伙伴已加入')).toBeVisible()
  await expect(page.getByRole('link', { name: '登录并继续' })).toBeVisible()
  expect(calls.map((call) => call.path)).toEqual(['/user/get', '/tripInvitation/preview'])
})

test('失效邀请码不展示旅行摘要', async ({ page }) => {
  const { calls } = await fixture(page, { revoked: true })
  await page.goto(inviteUrl)
  await expect(page.getByRole('alert')).toHaveText('邀请码已撤销')
  await expect(page.getByRole('heading', { name: '杭州同行' })).toHaveCount(0)
  expect(calls.some((call) => call.path === '/trip/get')).toBe(false)
})

test('登录返回邀请后，加入前重新校验；加入成功才读取受保护的旅行详情', async ({ page }) => {
  const { calls } = await fixture(page)
  await page.goto(inviteUrl)
  await page.getByRole('link', { name: '登录并继续' }).click()
  await page.getByLabel('账号', { exact: true }).fill('guest')
  await page.getByLabel('密码', { exact: true }).fill('example-password')
  await page.getByRole('button', { name: '登录 TripMate' }).click()
  await expect(page).toHaveURL(/\/invitation\?/)
  await page.getByLabel('你的本次旅行预算（元）').fill('800')
  await page.getByRole('button', { name: '确认加入这趟旅行' }).click()
  await expect(page).toHaveURL(/\/trips\/202$/)
  await expect(page.getByRole('heading', { name: '杭州同行' })).toBeVisible()
  const joinIndex = calls.findIndex((call) => call.path === '/tripMember/add')
  expect(calls[joinIndex - 1].path).toBe('/tripInvitation/preview')
  expect(calls.slice(0, joinIndex).some((call) => call.path === '/trip/get')).toBe(false)
})

test('预览后邀请码被撤销时，重新校验阻止加入', async ({ page }) => {
  const { state, calls } = await fixture(page, { loggedIn: true })
  await page.goto(inviteUrl)
  await page.getByLabel('你的本次旅行预算（元）').fill('800')
  state.revoked = true
  await page.getByRole('button', { name: '确认加入这趟旅行' }).click()
  await expect(page.getByRole('alert')).toHaveText('邀请码已撤销')
  expect(calls.filter((call) => call.path === '/tripInvitation/preview')).toHaveLength(2)
  expect(calls.some((call) => call.path === '/tripMember/add' || call.path === '/trip/get')).toBe(
    false,
  )
})

test('已加入成员可从邀请直接进入房间，无需再次提交加入', async ({ page }) => {
  const { calls } = await fixture(page, { loggedIn: true, joined: true })
  await page.goto(inviteUrl)
  await expect(page.getByText('你已经在旅行小队里啦。')).toBeVisible()
  await page.getByRole('link', { name: '进入旅行房间' }).click()
  await expect(page).toHaveURL(/\/trips\/202$/)
  await expect(page.getByRole('heading', { name: '杭州同行' })).toBeVisible()
  expect(calls.some((call) => call.path === '/tripMember/add')).toBe(false)
})

for (const path of ['/trips/202', '/trips/202/itinerary']) {
  test(`非成员直接打开 ${path} 显示权限错误且不加载私有内容`, async ({ page }) => {
    const { calls } = await fixture(page, { loggedIn: true })
    await page.goto(path)
    await expect(page.getByText('你已不在此旅行小队中', { exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: '杭州同行' })).toHaveCount(0)
    expect(calls.every((call) => ['/user/get', '/trip/get'].includes(call.path))).toBe(true)
  })
}
