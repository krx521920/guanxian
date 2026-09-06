import { test, expect, type Page } from '@playwright/test'

async function openChat(page: Page) {
  await page.goto('/tests/ui/assistant-preview.html')
  await page.getByRole('button', { name: '打开管线智能助手' }).click()
}
async function send(page: Page, text: string) {
  await page.getByLabel('向管线智能助手提问').fill(text)
  await page.getByRole('button', { name: '发送问题' }).click()
}

test('keeps interrupted output, retries once in a new conversation, preserves a single user message', async ({ page }) => {
  await openChat(page)
  await send(page, '测试断流')
  await expect(page.getByRole('alert')).toContainText('已保留收到的内容')
  await expect(page.locator('.assistant-message.assistant').last()).toContainText('本地模拟回答')
  await page.getByRole('button', { name: '重新回答（新会话）' }).click()
  await expect(page.getByText('追踪编号 local-preview-only')).toBeVisible()
  await expect(page.locator('.assistant-message.user')).toHaveCount(1)
  const requests = await page.evaluate(() => window.__guanxianPreview.requests)
  expect(requests).toHaveLength(2)
  expect(requests[0].conversationId).not.toEqual(requests[1].conversationId)
  await page.screenshot({ path: '../../test-results/assistant-output/complete.png', fullPage: true })
})

test('stop retains partial content and never displays a completed trace', async ({ page }) => {
  await openChat(page)
  await send(page, '测试停止')
  await expect(page.getByText('正在输出 · 内容尚未完成')).toBeVisible()
  await page.getByRole('button', { name: '停止回答' }).click()
  await expect(page.getByText('已停止，以上为未完成内容。')).toBeVisible()
  await expect(page.locator('.assistant-trace')).toHaveCount(0)
  await expect(page.getByLabel('向管线智能助手提问')).toBeEnabled()
  await page.screenshot({ path: '../../test-results/assistant-output/stopped.png', fullPage: true })
})

test('scrolling up stays put while more text arrives and latest button restores follow mode', async ({ page }) => {
  await openChat(page)
  await send(page, '测试长回答')
  const list = page.getByLabel('聊天记录')
  await expect.poll(() => list.evaluate(el => el.scrollHeight - el.clientHeight)).toBeGreaterThan(180)
  await list.evaluate(el => { el.scrollTop = 0; el.dispatchEvent(new Event('scroll')) })
  await expect(page.getByRole('button', { name: '查看最新回答 ↓' })).toBeVisible()
  const before = await list.textContent()
  await expect.poll(() => list.textContent()).not.toEqual(before)
  expect(await list.evaluate(el => el.scrollTop)).toBe(0)
  await page.getByRole('button', { name: '查看最新回答 ↓' }).click()
  await expect(page.getByRole('button', { name: '查看最新回答 ↓' })).toHaveCount(0)
  await page.getByRole('button', { name: '停止回答' }).click()
})

test('preferences are sent, composition Enter is not sent, and mobile remains usable', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await openChat(page)
  await page.getByText('回答设置 · 自动详略').click()
  await page.getByLabel('输出详略', { exact: true }).selectOption('BRIEF')
  await page.getByLabel('固定任务目标与约束').fill('客户演示，只用虚构数据')
  const input = page.getByLabel('向管线智能助手提问')
  await input.fill('简短解释')
  await input.dispatchEvent('keydown', { key: 'Enter', isComposing: true, keyCode: 229 })
  await expect(page.locator('.assistant-message.user')).toHaveCount(0)
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByText('追踪编号 local-preview-only')).toBeVisible()
  const requests = await page.evaluate(() => window.__guanxianPreview.requests)
  expect(requests[0]).toMatchObject({ responseDetail: 'BRIEF', taskGoal: '客户演示，只用虚构数据' })
  const box = await page.getByRole('dialog', { name: '管线智能助手' }).boundingBox()
  expect(box!.x + box!.width).toBeLessThanOrEqual(390)
  expect(box!.y + box!.height).toBeLessThanOrEqual(844)
  await page.screenshot({ path: '../../test-results/assistant-output/mobile.png', fullPage: true })
})
