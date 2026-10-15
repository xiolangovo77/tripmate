import { test, expect } from '@playwright/test'
import { randomBytes } from 'node:crypto'

test('真实 AI 界面：生成、按天查看、@AI、采纳、忽略和最终确认', async ({ page }, testInfo) => {
  test.skip(process.env.TEST_AI !== '1', '设置 TEST_AI=1 时才调用真实大模型')
  test.setTimeout(600000)
  const account = `ui_ai_${Date.now().toString(36)}`
  const password = randomBytes(12).toString('hex')
  const runtimeErrors = []
  page.on('pageerror', (error) => runtimeErrors.push(error.message))
  const signup = await page.request.post('/api/user/register', {
    data: { userAccount: account, userPassword: password, checkPassword: password },
  })
  expect((await signup.json()).code).toBe(0)
  await page.goto('/login?redirect=/')
  await page.getByLabel('账号', { exact: true }).fill(account)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录 TripMate' }).click()
  await expect(page).toHaveURL('http://127.0.0.1:5173/')
  await page.getByLabel('这次想去哪里').fill('杭州')
  await page.getByLabel('计划玩几天').fill('2')
  await page.getByLabel('每人预算').fill('800')
  await page.getByRole('button', { name: '开始我们的旅行' }).click()
  await page.getByRole('link', { name: '进入 AI 行程' }).click()
  await page.getByRole('button', { name: '生成 AI 行程' }).click()
  await expect(page.getByText('正在综合成员偏好生成行程')).toBeVisible()
  await expect(page.getByRole('heading', { name: '每天怎么玩' })).toBeVisible({ timeout: 190000 })
  await page.getByRole('button', { name: '第 2 天', exact: true }).click()
  await expect(page.locator('.event').first()).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('itinerary-desktop.png'), fullPage: true })
  await page
    .getByLabel('说说你的想法')
    .fill(
      '@AI 请修改现有行程：第二天下午增加一处免费的自然景点散步，减少购物安排。请记录为待处理的修改建议。',
    )
  await page.getByRole('button', { name: '发送 ↗', exact: true }).click()
  await expect(page.getByText('AI 正在整理想法')).toBeVisible()
  await expect(page.locator('.suggestion').first()).toBeVisible({ timeout: 190000 })
  await page.locator('.suggestion input').first().check()
  await page.getByRole('button', { name: '采纳并更新行程' }).click()
  await expect(page.getByText('AI 正在根据所选建议更新行程')).toBeVisible()
  await expect(page.getByText('行程已更新，请查看新的草案')).toBeVisible({ timeout: 190000 })
  await expect(page.getByRole('heading', { name: '每天怎么玩' })).toBeVisible()
  await page.getByLabel('建议状态').selectOption('applied')
  await expect(page.locator('.suggestion').first()).toBeVisible()
  await page.getByLabel('建议状态').selectOption('pending')
  await page
    .getByLabel('说说你的想法')
    .fill('@AI 请修改行程：第一天晚餐增加一个当地素食餐馆，作为待处理的行程修改建议。')
  await page.getByRole('button', { name: '发送 ↗', exact: true }).click()
  await expect(page.locator('.suggestion').first()).toBeVisible({ timeout: 190000 })
  await page.locator('.suggestion input').first().check()
  await page.getByRole('button', { name: '忽略所选建议' }).click()
  await expect(page.getByText('建议已忽略')).toBeVisible()
  await page.getByRole('button', { name: '确认这份行程' }).click()
  await page.getByRole('button', { name: '确认行程', exact: true }).click()
  await expect(page.getByRole('button', { name: '已确认行程' })).toBeDisabled()
  await page.reload()
  await expect(page.getByRole('button', { name: '已确认行程' })).toBeDisabled()
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: testInfo.outputPath('itinerary-mobile.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(runtimeErrors).toEqual([])
})
