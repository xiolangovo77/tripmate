import { test, expect } from '@playwright/test'
import { randomBytes } from 'node:crypto'

const base = 'http://127.0.0.1:5173'
async function registerAndLogin(page, account, password, redirect = '/my-trips') {
  await page.goto(`${base}/register?redirect=${encodeURIComponent(redirect)}`)
  await page.getByLabel('账号', { exact: true }).fill(account)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByLabel('确认密码').fill(password)
  await page.getByRole('button', { name: '注册账号' }).click()
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
  await page.getByLabel('账号', { exact: true }).fill(account)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录 TripMate' }).click()
  await expect(page).toHaveURL(`${base}${redirect}`)
}
async function noOverflow(page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
}

test('桌面端真实双用户流程、刷新恢复与权限控制', async ({ browser, page }, testInfo) => {
  const runtimeErrors = []
  page.on('pageerror', (error) => runtimeErrors.push(error.message))
  const suffix = Date.now().toString(36)
  const password = randomBytes(12).toString('hex')
  await page.goto('/')
  await expect(page.getByRole('heading', { name: /一群人的旅行/ })).toBeVisible()
  await expect
    .poll(() => page.locator('.poster img').evaluate((img) => img.naturalWidth))
    .toBeGreaterThan(0)
  await page.screenshot({ path: testInfo.outputPath('home-desktop.png'), fullPage: true })
  await registerAndLogin(page, `ui_${suffix}_owner`, password)
  await expect(page.getByText('还没有符合条件的旅行')).toBeVisible()
  await page.getByRole('button', { name: '编辑个人偏好' }).click()
  await page.getByLabel('昵称', { exact: true }).fill('旅行发起人测试')
  await page.getByRole('button', { name: '保存个人信息与偏好' }).click()
  await expect(page.getByText('偏好已保存，新建或加入旅行时会自动带入')).toBeVisible()
  await page.evaluate(() => sessionStorage.clear())
  await page.reload()
  await page.getByRole('button', { name: '编辑个人偏好' }).click()
  await expect(page.getByLabel('昵称', { exact: true })).toHaveValue('旅行发起人测试')
  const freshTab = await page.context().newPage()
  freshTab.on('pageerror', (error) => runtimeErrors.push(error.message))
  await freshTab.goto(base + '/my-trips')
  await freshTab.getByRole('button', { name: '编辑个人偏好' }).click()
  await expect(freshTab.getByLabel('昵称', { exact: true })).toHaveValue('旅行发起人测试')
  expect(
    await freshTab.evaluate(() => sessionStorage.getItem('tripmate.user-display.v1')),
  ).toBeNull()
  await freshTab.close()
  await page.goto('/')
  await page.getByLabel('这次想去哪里').fill('苏州')
  await page.getByLabel('计划玩几天').fill('2')
  await page.getByLabel('每人预算').fill('700')
  await page.getByRole('button', { name: '开始我们的旅行' }).click()
  await expect(page).toHaveURL(/\/trips\/\d+$/)
  const roomUrl = page.url()
  await expect(page.getByRole('heading', { name: /旅行小队/ })).toBeVisible()
  await expect(page.getByText('旅行发起人测试（我）', { exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: /旅行小队/ })).toBeVisible()
  await page.getByRole('button', { name: '修改我的本次偏好' }).click()
  await page.getByLabel('本次预算').fill('680')
  await page.getByLabel('你偏好的旅行节奏').selectOption('relaxed')
  await page.getByRole('button', { name: '保存本次偏好' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByText('¥680', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '修改旅行基本信息' }).click()
  await page.getByLabel('旅行名称', { exact: true }).fill('苏州 · 好友慢游')
  await page.getByRole('button', { name: '保存旅行信息' }).click()
  await expect(page.getByRole('heading', { name: '苏州 · 好友慢游' })).toBeVisible()
  await page.getByRole('button', { name: '邀请朋友' }).click()
  await page.getByRole('button', { name: '创建邀请码' }).click()
  const preview = page.getByRole('link', { name: '预览邀请' })
  await expect(preview).toBeVisible()
  const invitePath = await preview.getAttribute('href')
  await page.getByRole('button', { name: '关闭弹窗' }).click()
  const guestContext = await browser.newContext({ viewport: { width: 390, height: 844 } })
  const guest = await guestContext.newPage()
  guest.on('pageerror', (error) => runtimeErrors.push(error.message))
  await guest.goto(base + invitePath)
  await expect(guest.getByText('邀请验证通过')).toBeVisible()
  await expect
    .poll(() => guest.locator('.postcard img').evaluate((img) => img.naturalWidth))
    .toBeGreaterThan(0)
  await guest.screenshot({ path: testInfo.outputPath('invitation-mobile.png'), fullPage: true })
  await noOverflow(guest)
  await registerAndLogin(guest, `ui_${suffix}_guest`, password, invitePath)
  await guest.getByLabel('你的本次旅行预算').fill('600')
  const joinRequest = guest.waitForRequest((request) =>
    request.url().endsWith('/api/tripMember/add'),
  )
  await guest.getByRole('button', { name: '确认加入这趟旅行' }).click()
  expect((await joinRequest).postDataJSON()).toEqual({
    tripId: roomUrl.split('/').at(-1),
    inviteCode: new URL(base + invitePath).searchParams.get('inviteCode'),
    budgetLimit: 600,
  })
  await expect(guest).toHaveURL(roomUrl)
  await expect(guest.getByText('2 人已加入')).toBeVisible()
  await expect(guest.getByRole('button', { name: '修改旅行基本信息' })).toHaveCount(0)
  await noOverflow(guest)
  const aiRequests = []
  guest.on('request', (request) => {
    if (request.url().includes('/tripMessage/addAi')) aiRequests.push(request.url())
  })
  const content = '<img src=x onerror=alert(1)> 普通聊天，期待出发'
  await guest.getByLabel('说说你的想法').fill(content)
  await guest.getByRole('button', { name: '发送 ↗', exact: true }).click()
  await expect(guest.getByText(content, { exact: true })).toBeVisible()
  expect(aiRequests).toHaveLength(0)
  await guest.screenshot({ path: testInfo.outputPath('room-mobile.png'), fullPage: true })
  await page.getByRole('button', { name: '刷新成员' }).click()
  await expect(page.getByText('2 人已加入')).toBeVisible()
  await page.getByRole('button', { name: '刷新消息' }).click()
  await expect(page.getByText(content, { exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('room-desktop.png'), fullPage: true })
  await page.getByRole('link', { name: '进入 AI 行程' }).click()
  await expect(page.getByRole('button', { name: '生成 AI 行程', exact: false })).toBeVisible()
  await expect(page.getByText('此状态下暂无 AI 建议')).toBeVisible()
  await guest.goto(roomUrl + '/itinerary')
  await expect(guest.getByText('和伙伴一起分享想法，等待发起人生成行程。')).toBeVisible()
  await expect(guest.getByRole('button', { name: '生成 AI 行程' })).toHaveCount(0)
  await guest.goto(base + '/my-trips')
  await expect(guest.getByRole('heading', { name: '我的旅行 / JOURNAL' })).toBeVisible()
  await guest.getByRole('button', { name: '退出', exact: true }).click()
  await expect(guest).toHaveURL(base + '/')
  await guest.goto(roomUrl)
  await expect(guest).toHaveURL(/\/login\?redirect=/)
  await page.goto(roomUrl)
  await page.getByRole('button', { name: '邀请朋友' }).click()
  await page.getByRole('button', { name: '撤销邀请' }).click()
  await page.getByRole('button', { name: '确认撤销' }).click()
  await expect(page.getByText('还没有有效邀请码')).toBeVisible()
  await guest.goto(base + invitePath)
  await expect(guest.getByText('邀请码已撤销', { exact: true })).toBeVisible()
  await guestContext.close()
  expect(runtimeErrors).toEqual([])
})

test('移动端首页、登录页与无效链接没有横向溢出', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  for (const path of ['/', '/login', '/invitation', '/unknown']) {
    await page.goto(path)
    await expect(page.locator('main')).toBeVisible()
    await noOverflow(page)
  }
  await page.goto('/')
  await page.screenshot({ path: testInfo.outputPath('home-mobile.png'), fullPage: true })
})
