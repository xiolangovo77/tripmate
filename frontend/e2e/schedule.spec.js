// 浏览器交互与请求契约测试；响应夹具不代表真实数据库联调。
import { test, expect } from '@playwright/test'

async function fixture(page, hasItinerary = false) {
  await page.addInitScript(() => {
    window.EventSource = undefined
  })
  const requests = []
  const trip = {
    ownerId: '101',
    title: '杭州3日游',
    departureLocation: '上海',
    destination: '杭州',
    departureDate: '2028-02-28',
    returnDate: '2028-03-01',
    departurePeriod: 'morning',
    returnPeriod: 'evening',
    travelDays: 3,
    hasItinerary,
    status: 'planning',
    tripMembers: [
      {
        userId: '101',
        nickname: '小旅',
        budgetLimit: 800,
        favoriteActivities: '["自然"]',
        travelPace: 'balanced',
      },
    ],
  }
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const path = new URL(route.request().url()).pathname.replace('/api', '')
      let data
      if (path === '/user/get')
        data = {
          id: '101',
          userAccount: 'schedule-fixture',
          nickname: '小旅',
          favoriteActivities: '["自然"]',
        }
      else if (path === '/trip/get') data = trip
      else if (path === '/trip/202/members') data = trip.tripMembers
      else if (path === '/itinerary/busy') data = false
      else if (path === '/trip/add' || path === '/trip/update') {
        const body = route.request().postDataJSON()
        requests.push(body)
        Object.assign(trip, body)
        data = path === '/trip/add' ? '202' : true
      } else if (
        [
          '/tripMessage/page',
          '/tripMessage/latest',
          '/tripMessage/getAiByChangeStatus',
          '/trip/page',
        ].includes(path)
      )
        data = []
      else throw new Error(`Unexpected request ${path}`)
      await route.fulfill({ json: { code: 0, data } })
    },
  )
  return requests
}

test('移动端创建：自动计算闰年天数，校验时段，只提交新字段', async ({ page }, testInfo) => {
  const requests = await fixture(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await page.getByLabel('从哪里出发').fill('上海')
  await page.getByLabel('这次想去哪里').fill('杭州')
  await page.getByLabel('出发日期', { exact: true }).fill('2028-02-28')
  await page.getByLabel('返回日期', { exact: true }).fill('2028-03-01')
  await expect(page.getByText(/共 3 天/)).toBeVisible()
  await page.getByLabel('每人预算').fill('800')
  await page.getByLabel('返回日期', { exact: true }).fill('2028-02-28')
  await page.getByLabel('出发时段').selectOption('evening')
  await page.getByLabel('返回时段').selectOption('morning')
  await page.getByRole('button', { name: '开始我们的旅行' }).click()
  await expect(page.getByRole('alert')).toHaveText('同日往返时，返回时段不能早于出发时段')
  expect(requests).toHaveLength(0)
  await page.getByLabel('返回日期', { exact: true }).fill('2028-03-01')
  await page.getByLabel('出发时段').selectOption('morning')
  await page.getByLabel('返回时段').selectOption('evening')
  await page.screenshot({ path: testInfo.outputPath('dates-mobile.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.getByRole('button', { name: '开始我们的旅行' }).click()
  await expect(page).toHaveURL(/\/trips\/202$/)
  expect(requests).toEqual([
    {
      departureLocation: '上海',
      destination: '杭州',
      departureDate: '2028-02-28',
      returnDate: '2028-03-01',
      departurePeriod: 'morning',
      returnPeriod: 'evening',
      budgetLimit: 800,
    },
  ])
  await expect(page.getByText('2028-02-28 上午 — 2028-03-01 晚上')).toBeVisible()
})

test('房间编辑往返日期，已有行程时锁定日期但仍可保存标题', async ({ page }) => {
  const requests = await fixture(page, true)
  await page.goto('/trips/202')
  await page.getByRole('button', { name: '修改旅行基本信息' }).click()
  await expect(page.getByLabel('出发日期', { exact: true })).toBeDisabled()
  await expect(page.getByLabel('返回时段')).toBeDisabled()
  await page.getByLabel('旅行名称', { exact: true }).fill('新的旅行标题')
  await page.getByRole('button', { name: '保存旅行信息' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(requests[0].title).toBe('新的旅行标题')
  expect(requests[0].returnDate).toBe('2028-03-01')
  expect(requests[0]).not.toHaveProperty('travelDays')
})

test('未生成行程可以更改日期和地点，保存包含完整路线', async ({ page }, testInfo) => {
  const requests = await fixture(page)
  await page.goto('/trips/202')
  await page.getByRole('button', { name: '修改旅行基本信息' }).click()
  await page.getByLabel('从哪里出发').fill('南京')
  await page.getByLabel('返回日期', { exact: true }).fill('2028-03-02')
  await expect(page.getByText(/共 4 天/)).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('edit-dates-desktop.png'), fullPage: true })
  await page.getByRole('button', { name: '保存旅行信息' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(requests[0].departureLocation).toBe('南京')
  expect(requests[0].returnDate).toBe('2028-03-02')
})
