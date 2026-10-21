import { test, expect } from '@playwright/test'

const questionId = '9223372036854775701'
const replyId = '9223372036854775702'
const question = {
  id: questionId,
  senderId: '102',
  role: 'user',
  content: '@AI 第二天可以安排博物馆吗？',
  recallStatus: 'normal',
  changeStatus: 'none',
  createTime: '2027-04-01T09:00:00',
}
const reply = {
  id: replyId,
  role: 'assistant',
  content: '可以，建议第二天上午参观博物馆。',
  replyToId: questionId,
  recallStatus: 'normal',
  changeStatus: 'none',
  createTime: '2027-04-01T10:00:00',
}

async function fixture(page, messages, readReferences = () => [question]) {
  const calls = []
  const members = [
    { userId: '101', nickname: '发起人', role: 'owner', status: 'joined' },
    { userId: '102', nickname: '小林', role: 'member', status: 'joined' },
  ]
  // 固定使用轮询，避免测试夹具建立真实 SSE 连接。
  await page.addInitScript(() => {
    window.EventSource = undefined
  })
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const url = new URL(route.request().url())
      let data
      if (url.pathname === '/api/user/get')
        data = { id: '101', userAccount: 'fixture', nickname: '发起人' }
      else if (url.pathname === '/api/trip/get')
        data = {
          id: '202',
          ownerId: '101',
          title: '杭州同行',
          destination: '杭州',
          departureLocation: '上海',
          travelDays: 2,
          tripMembers: members,
        }
      else if (url.pathname === '/api/trip/202/members') data = members
      else if (url.pathname === '/api/tripMessage/page') {
        calls.push({ type: 'page', cursor: url.searchParams.get('lastId') })
        data = url.searchParams.has('lastId') ? [question] : messages
      } else if (url.pathname === '/api/tripMessage/latest') data = []
      else if (url.pathname === '/api/tripMessage/byIds') {
        calls.push({ type: 'references', ids: url.searchParams.get('ids') })
        data = readReferences()
        if (data instanceof Error) {
          await route.fulfill({ json: { code: 50000, message: data.message } })
          return
        }
      } else throw new Error(`意外请求：${url.pathname}`)
      await route.fulfill({ json: { code: 0, data } })
    },
  )
  await page.goto('/trips/202')
  return calls
}

test('AI 回复准确引用提问者与原文，普通消息不显示引用', async ({ page }) => {
  const calls = await fixture(page, [reply, question])
  const quote = page.locator('.chat.assistant .chat-reply')
  await expect(quote).toContainText('回复 小林')
  await expect(quote).toContainText(question.content)
  await expect(page.locator('.chat.peer .chat-reply')).toHaveCount(0)
  expect(calls.filter((call) => call.type === 'references')).toHaveLength(0)
})

test('跨页引用自动补查，保留历史分页边界且移动端不溢出', async ({ page }, testInfo) => {
  const history = Array.from({ length: 29 }, (_, index) => ({
    id: String(5000 + index),
    senderId: '101',
    role: 'user',
    content: `讨论 ${index}`,
    recallStatus: 'normal',
    changeStatus: 'none',
    createTime: '2027-04-01T09:30:00',
  })).reverse()
  const calls = await fixture(page, [reply, ...history])
  const quote = page.locator('.chat.assistant .chat-reply')
  await expect(quote).toContainText(question.content)
  await expect(page.locator('.chat')).toHaveCount(30)
  expect(
    calls.filter((call) => call.type === 'references').every((call) => call.ids === questionId),
  ).toBe(true)
  await page.getByRole('button', { name: '加载更早的消息' }).click()
  await expect(page.locator('.chat')).toHaveCount(31)
  expect(calls.filter((call) => call.type === 'page').at(-1).cursor).toBe('5000')
  await page.setViewportSize({ width: 390, height: 844 })
  await quote.scrollIntoViewIfNeeded()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page
    .locator('#trip-chat')
    .screenshot({ path: testInfo.outputPath('chat-reference-mobile.png') })
})

test('引用加载失败可刷新恢复，撤回和缺失的原消息有明确提示', async ({ page }) => {
  let state = 'error'
  await fixture(page, [reply], () =>
    state === 'error'
      ? new Error('网络故障')
      : state === 'recalled'
        ? [{ ...question, recallStatus: 'recalled' }]
        : [],
  )
  const quote = page.locator('.chat.assistant .chat-reply')
  await expect(quote).toContainText('原消息暂时无法加载，请刷新消息重试')
  await expect(page.locator('.chat.assistant')).toContainText(reply.content)
  state = 'recalled'
  await page.getByRole('button', { name: '刷新消息', exact: true }).click()
  await expect(quote).toContainText('原消息已撤回')
  await expect(quote).not.toContainText(question.content)
  state = 'missing'
  await page.getByRole('button', { name: '刷新消息', exact: true }).click()
  await expect(quote).toContainText('原消息已不存在或无法查看')
})
