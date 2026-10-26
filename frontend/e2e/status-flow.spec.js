// 用响应夹具注入分步失败；不调用真实 AI，不替代真实接口联调。
import { test, expect } from '@playwright/test'

async function fixture(
  page,
  { owner = true, generateFails = false, statusFails = 0, leaveFails = 0 } = {},
) {
  await page.addInitScript(() => {
    window.EventSource = undefined
  })
  const calls = []
  let plan = null
  let statusAttempts = 0
  let leaveAttempts = 0
  const user = {
    id: owner ? '101' : '102',
    userAccount: 'fixture',
    nickname: '旅行伙伴',
    favoriteActivities: '["自然"]',
  }
  const members = ['101', '102'].map((userId) => ({
    userId,
    nickname: userId === '101' ? '发起人' : '伙伴',
    travelPace: 'relaxed',
    budgetLimit: 800,
    favoriteActivities: '["自然"]',
  }))
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const path = new URL(route.request().url()).pathname.replace('/api', '')
      const body = route.request().postDataJSON()
      let data
      let failure
      if (path === '/user/get') data = user
      else if (path === '/trip/get')
        data = {
          ownerId: '101',
          title: '杭州同行',
          destination: '杭州',
          departureLocation: '上海',
          departureDate: '2027-04-01',
          returnDate: '2027-04-02',
          departurePeriod: 'morning',
          returnPeriod: 'evening',
          travelDays: 2,
          tripMembers: members,
        }
      else if (path === '/trip/getPrompt') data = '测试旅行提示词'
      else if (path === '/trip/202/members') data = members
      else if (path === '/itinerary/getByTripId') data = plan
      else if (path === '/itinerary/busy') data = false
      else if (path === '/itinerary/getIdByTripId') data = plan ? '303' : null
      else if (path === '/itinerary/get') data = plan
      else if (path === '/itinerary/add') {
        calls.push({ path, body })
        if (generateFails) failure = '生成失败'
        else {
          plan = {
            id: '303',
            tripId: '202',
            title: '新生成的行程',
            summary: '已保存的行程',
            status: 'draft',
            estimatedCostPerPerson: 800,
            itineraryItemVOList: [],
          }
          data = '303'
        }
      } else if (path === '/trip/updateStatus') {
        calls.push({ path, body })
        if (++statusAttempts <= statusFails) failure = '状态写入失败'
        else data = true
      } else if (path === '/tripMember/updateStatus') {
        calls.push({ path, body })
        if (++leaveAttempts <= leaveFails) failure = '成员退出失败'
        else data = true
      } else if (
        [
          '/trip/page',
          '/tripMessage/page',
          '/tripMessage/latest',
          '/tripMessage/getAiByChangeStatus',
        ].includes(path)
      )
        data = []
      else throw new Error(`意外请求：${path}`)
      await route.fulfill({ json: failure ? { code: 50000, message: failure } : { code: 0, data } })
    },
  )
  return calls
}

test('创建行程后确认旅行；状态失败仅重试状态，不重新生成', async ({ page }) => {
  const calls = await fixture(page, { statusFails: 1 })
  await page.goto('/trips/202/itinerary')
  await page.getByRole('button', { name: '生成 AI 行程' }).click()
  await expect(page.getByText('行程已生成，但旅行状态尚未确认')).toBeVisible()
  await expect(page.getByRole('heading', { name: '新生成的行程' })).toBeVisible()
  expect(calls.map((call) => call.path)).toEqual(['/itinerary/add', '/trip/updateStatus'])
  expect(calls[1].body).toEqual({ id: '202', status: 'confirmed' })
  await page.getByRole('button', { name: '重试确认旅行状态' }).click()
  await expect(page.getByRole('status').filter({ hasText: '行程已生成，旅行已确认' })).toBeVisible()
  await expect(page.getByRole('button', { name: '重试确认旅行状态' })).toHaveCount(0)
  expect(calls.filter((call) => call.path === '/itinerary/add')).toHaveLength(1)
  expect(calls.filter((call) => call.path === '/trip/updateStatus')).toHaveLength(2)
})

test('行程创建失败不修改旅行状态', async ({ page }) => {
  const calls = await fixture(page, { generateFails: true })
  await page.goto('/trips/202/itinerary')
  await page.getByRole('button', { name: '生成 AI 行程' }).click()
  await expect(page.getByText('生成失败', { exact: true })).toBeVisible()
  expect(calls.map((call) => call.path)).toEqual(['/itinerary/add'])
})

test('取消旅行失败时不退出发起人', async ({ page }) => {
  const calls = await fixture(page, { statusFails: 1 })
  await page.goto('/trips/202')
  await page.getByRole('button', { name: '取消并退出旅行' }).click()
  await page.getByRole('button', { name: '确认取消并退出' }).click()
  await expect(page.getByText('未能取消旅行，尚未执行退出')).toBeVisible()
  expect(calls).toEqual([{ path: '/trip/updateStatus', body: { id: '202', status: 'cancelled' } }])
  await expect(page).toHaveURL(/\/trips\/202$/)
})

test('已取消但退出失败时，只重试成员退出', async ({ page }) => {
  const calls = await fixture(page, { leaveFails: 1 })
  await page.goto('/trips/202')
  await page.getByRole('button', { name: '取消并退出旅行' }).click()
  await page.getByRole('button', { name: '确认取消并退出' }).click()
  await expect(page.getByText('旅行已取消，但退出尚未完成')).toBeVisible()
  expect(calls.map((call) => call.path)).toEqual(['/trip/updateStatus', '/tripMember/updateStatus'])
  await page.getByRole('button', { name: '重试退出' }).click()
  await expect(page).toHaveURL(/\/my-trips$/)
  expect(calls.filter((call) => call.path === '/trip/updateStatus')).toHaveLength(1)
  expect(calls.filter((call) => call.path === '/tripMember/updateStatus')).toHaveLength(2)
  expect(calls[2].body).toEqual({ tripId: '202', userId: '101', status: 'left' })
})

test('普通成员退出不取消整趟旅行', async ({ page }) => {
  const calls = await fixture(page, { owner: false })
  await page.goto('/trips/202')
  await expect(page.getByRole('button', { name: '取消并退出旅行' })).toHaveCount(0)
  await page.getByRole('button', { name: '退出旅行', exact: true }).click()
  await page.getByRole('button', { name: '确认退出', exact: true }).click()
  await expect(page).toHaveURL(/\/my-trips$/)
  expect(calls).toEqual([
    { path: '/tripMember/updateStatus', body: { tripId: '202', userId: '102', status: 'left' } },
  ])
})
