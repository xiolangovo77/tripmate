import { test, expect } from '@playwright/test'
import { randomBytes } from 'node:crypto'

test('真实服务：创建草稿、筛选、聊天快捷键、刷新保留输入和弹窗焦点', async ({ page }, testInfo) => {
  const account = `ui_ux_${Date.now().toString(36)}`
  const password = randomBytes(12).toString('hex')
  const registration = await page.request.post('/api/user/register', { data: { userAccount: account, userPassword: password, checkPassword: password } })
  expect((await registration.json()).code).toBe(0)
  const runtimeErrors = []
  page.on('pageerror', error => runtimeErrors.push(error.message))
  await page.goto('/')
  await page.getByRole('button', { name: /苏州.*在小巷里慢下来/ }).click()
  await expect(page.getByLabel('这次想去哪里')).toHaveValue('苏州')
  await expect(page.getByLabel('计划玩几天')).toHaveValue('3')
  await expect(page.getByLabel('每人预算')).toHaveValue('1200')
  await page.getByLabel('每人预算').fill('1500')
  await page.getByRole('button', { name: '登录并开始旅行' }).click()
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
  await page.getByLabel('账号', { exact: true }).fill(account)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录 TripMate' }).click()
  await expect(page).toHaveURL(/\/#create$/)
  await expect(page.getByLabel('每人预算')).toHaveValue('1500')
  await expect(page.getByLabel('这次想去哪里')).toHaveValue('苏州')
  await page.getByRole('button', { name: '开始我们的旅行' }).click()
  await expect(page.getByRole('heading', { name: /旅行小队/ })).toBeVisible()
  const roomUrl = page.url()
  expect(await page.evaluate(() => sessionStorage.getItem('tripmate.create-draft.v1'))).toBeNull()
  const composer = page.getByLabel('说说你的想法')
  await composer.fill('刷新也不会丢掉的想法')
  await page.getByRole('button', { name: '刷新成员' }).click()
  await expect(page.getByText('正在更新…', { exact: true })).toHaveCount(0)
  await expect(composer).toHaveValue('刷新也不会丢掉的想法')
  await composer.press('Control+Enter')
  await expect(page.getByText('刷新也不会丢掉的想法', { exact: true })).toBeVisible()
  await expect(composer).toHaveValue('')
  await expect(composer).toBeFocused()
  await page.getByRole('button', { name: '@AI', exact: true }).click()
  await expect(page.getByText('AI 将参与回复')).toBeVisible()
  await composer.fill('')
  const preferenceButton = page.getByRole('button', { name: '修改我的本次偏好' })
  await preferenceButton.click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(preferenceButton).toBeFocused()
  await page.getByRole('button', { name: '邀请朋友' }).click()
  await page.getByRole('button', { name: '创建邀请码' }).click()
  await expect(page.getByRole('link', { name: '预览邀请' })).toBeVisible()
  let revocations = 0
  page.on('request', request => { if (request.url().includes('/tripInvitation/updateStatus')) revocations++ })
  const revoke = page.getByRole('button', { name: '撤销邀请', exact: true })
  await revoke.click()
  const confirmation = page.getByRole('dialog', { name: '收回这份邀请？' })
  await expect(confirmation).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(confirmation).toHaveCount(0)
  await expect(revoke).toBeFocused()
  expect(revocations).toBe(0)
  await expect(page.getByRole('dialog')).toHaveCount(1)
  await page.getByRole('button', { name: '关闭弹窗' }).click()
  expect(await page.evaluate(() => document.body.style.overflow)).toBe('')
  await page.goto('/my-trips')
  await expect(page.locator('.trip-list-card')).toHaveCount(1)
  await expect(page.locator('.nav-links .active')).toHaveCount(1)
  await page.getByRole('button', { name: '筛选旅行' }).click()
  await page.getByLabel('目的地', { exact: true }).fill('不存在的目的地')
  await page.getByRole('button', { name: '查询旅行', exact: true }).click()
  await expect(page.getByText('还没有符合条件的旅行')).toBeVisible()
  await page.getByRole('button', { name: '清空筛选，查看全部' }).click()
  await expect(page.locator('.trip-list-card')).toHaveCount(1)
  await page.getByRole('button', { name: '筛选旅行' }).click()
  await page.screenshot({ path: testInfo.outputPath('journal-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: testInfo.outputPath('journal-mobile.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.goto(roomUrl)
  await page.getByRole('button', { name: '邀请朋友' }).click()
  await page.getByRole('button', { name: '撤销邀请', exact: true }).click()
  await page.screenshot({ path: testInfo.outputPath('confirmation-mobile.png'), fullPage: true })
  await page.getByRole('button', { name: '先等等' }).click()
  expect(runtimeErrors).toEqual([])
})

test('行程展示夹具：按天切换、确认与移动端布局（不调用 AI）', async ({ page }, testInfo) => {
  const user = { id: '101', userAccount: 'fixture', nickname: '林间旅人' }
  const trip = { id: '202', ownerId: '101', title: '苏州 · 留一点时间给生活', destination: '苏州', travelDays: 2, tripMembers: [{ userId: '101', nickname: '林间旅人', role: 'owner', status: 'joined', travelPace: 'relaxed', budgetLimit: 1200, favoriteActivities: '["美食","自然"]' }] }
  const itinerary = { title: '巷子里的慢时光', summary: '从园林的清晨，到河边的晚风。留足步行和休息的时间，和朋友一起感受苏州。', status: 'draft', estimatedCostPerPerson: 860, itineraryItemVOList: [
    { id: '401', dayNumber: 1, sortOrder: 1, startTime: '09:00:00', endTime: '11:00:00', activityName: '在拙政园听见清晨', locationName: '拙政园', address: '苏州市姑苏区东北街', description: '沿着池塘慢慢走，看看光影怎样落在回廊里。', estimatedCostPerPerson: 80, activityType: '自然' },
    { id: '402', dayNumber: 1, sortOrder: 2, startTime: '12:00:00', endTime: '13:30:00', activityName: '一起吃碗苏式面', locationName: '平江路附近', description: '一碗热汤面，再留点肚子给沿路的小吃。', estimatedCostPerPerson: 40, activityType: '美食' },
    { id: '403', dayNumber: 2, sortOrder: 1, startTime: '10:00:00', endTime: '12:00:00', activityName: '把上午留给博物馆', locationName: '苏州博物馆', description: '提前预约，慢慢参观。', estimatedCostPerPerson: 0, activityType: '文化' },
  ] }
  let confirmations = 0
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    let data
    if (url.pathname.endsWith('/user/get')) data = user
    else if (url.pathname.endsWith('/trip/get')) data = trip
    else if (url.pathname.endsWith('/getIdByTripId')) data = '303'
    else if (url.pathname.endsWith('/itinerary/get')) data = itinerary
    else if (url.pathname.endsWith('/itinerary/confirmed')) {
      expect(route.request().method()).toBe('PUT')
      expect(url.searchParams.get('id')).toBe('303')
      confirmations++
      itinerary.status = 'confirmed'
      data = 1
    } else if (url.pathname.endsWith('/tripMessage/page') || url.pathname.endsWith('/getAiByChangeStatus')) data = []
    else throw new Error(`意外请求：${url.pathname}`)
    await route.fulfill({ json: { code: 0, data } })
  })
  await page.goto('/trips/202/itinerary')
  await expect(page.getByText('在拙政园听见清晨')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('itinerary-desktop-fixture.png'), fullPage: true })
  await page.getByRole('button', { name: '第 2 天', exact: true }).click()
  await expect(page.getByText('把上午留给博物馆')).toBeVisible()
  await expect(page.getByText('在拙政园听见清晨')).toHaveCount(0)
  await page.getByRole('button', { name: '确认这份行程' }).click()
  await page.getByRole('button', { name: '先等等' }).click()
  expect(confirmations).toBe(0)
  await page.getByRole('button', { name: '确认这份行程' }).click()
  await page.getByRole('button', { name: '确认行程', exact: true }).click()
  await expect(page.getByRole('button', { name: '已确认行程' })).toBeDisabled()
  expect(confirmations).toBe(1)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: testInfo.outputPath('itinerary-mobile-fixture.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.emulateMedia({ reducedMotion: 'reduce' })
  expect(await page.locator('.page-scene').evaluate(element => getComputedStyle(element).animationName)).toBe('none')
})
