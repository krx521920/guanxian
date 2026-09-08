import { expect, test } from '@playwright/test'
import { authenticatedPage, e2eUsers } from './support'

// Real OIDC, Nginx SSE and PostgreSQL. No API interception, remote model or production fixture writes.
test('真实登录后只读查询招采与活动，显示来源并按当前身份重新读取', async ({ browser }) => {
  const origin = new URL(process.env.E2E_WEB_BASE_URL?.trim() || 'http://127.0.0.1:18082').origin
  expect(origin, 'This test is for the isolated local/CI stack only').toBe('http://127.0.0.1:18082')
  const { context, page } = await authenticatedPage(browser, e2eUsers.associationAdmin)
  try {
    await page.goto('/members')
    await page.getByRole('button', { name: '打开管线智能助手' }).click()
    const stream = page.waitForResponse(r => r.url().endsWith('/api/v1/assistant/chat/stream') && r.request().method() === 'POST')
    await page.getByLabel('向管线智能助手提问').fill('查询近一年“E2E证据联调”招标和活动资料')
    await page.getByRole('button', { name: '发送问题' }).click()
    const response = await stream
    expect(response.status()).toBe(200)
    expect(response.headers()['content-type']).toContain('text/event-stream')
    // Chromium may discard consumed SSE bodies. Verify the real stream consumer instead
    // of reading CDP response.text(), and assert the fresh JSON response below.
    const receipts = page.locator('.query-receipt')
    await expect(receipts).toHaveCount(2)
    await expect(page.getByText('已按当前权限查询平台已入库的公开来源资料（未调用大模型）。', { exact: false })).toBeVisible()
    await expect(page.locator('.business-results')).not.toContainText('DO_NOT_EXPOSE_E2E_PRIVATE')
    await expect(page.locator('.business-results')).not.toContainText('E2E-SRC-FOREIGN')
    await expect(receipts.nth(0).locator('summary')).toContainText('共 1 条 · 展示 1 条')
    await expect(receipts.nth(1).locator('summary')).toContainText('共 1 条 · 展示 1 条')
    await expect(receipts.nth(0)).toContainText('候选单位，不等于中标')
    await expect(receipts.nth(1)).toContainText('不是平台确认合作')
    await expect(receipts.nth(0).getByRole('link', { name: '来源原文 ↗', exact: true }))
      .toHaveAttribute('href', 'https://example.test/e2e/E2E-SRC-TENDER')
    const fresh = page.waitForResponse(r => r.url().includes('/api/v1/source-directory?') && r.url().includes('recordId=60000000-0000-4000-8000-000000000001'))
    await receipts.nth(0).getByRole('button', { name: '重新核对资料', exact: true }).click()
    const reread = await fresh
    expect(reread.status()).toBe(200)
    const data = await reread.json()
    expect(data.data.total).toBe(1)
    expect(JSON.stringify(data)).not.toContain('DO_NOT_EXPOSE_E2E_PRIVATE')
    expect(data.data.items[0].id).toBe('60000000-0000-4000-8000-000000000001')
    await expect(receipts.nth(0).locator('.source-directory')).toContainText('E2E证据联调·虚构候选公示')
  } finally { await context.close() }
})
