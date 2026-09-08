import { test, expect } from '@playwright/test'

test('source cards show provenance, real totals and fresh scoped reread without treating candidates as awards', async ({ page }) => {
  await page.goto('/tests/ui/assistant-preview.html')
  await page.getByRole('button', { name: '打开管线智能助手' }).click()
  await page.getByLabel('向管线智能助手提问').fill('查询近一年招标资料')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.locator('.query-receipt summary')).toContainText('共 3 条 · 展示 1 条')
  const source = page.getByRole('region', { name: '公开资料来源' })
  await expect(source).toContainText('EV-FIXTURE')
  await expect(source).toContainText('不是今日实时复核')
  await expect(source.getByRole('link', { name: '来源原文 ↗', exact: true })).toHaveAttribute('href', 'https://example.test/notice')
  await expect(source.getByRole('link', { name: '来源原文 ↗', exact: true })).toHaveAttribute('rel', 'noopener noreferrer')
  await expect(page.locator('.business-card')).toContainText('企业份额未披露')
  await page.getByRole('button', { name: '重新核对资料', exact: true }).click()
  const directory = source.locator('.source-directory')
  await expect(directory).toContainText('重新读取的虚构公开资料')
  const heading = (await directory.getByRole('heading', { name: '外部招投标', exact: true }).boundingBox())!
  expect(heading.height).toBeLessThan(35)
  expect(await page.evaluate(() => window.__guanxianPreview.sourceReads[0])).toContain('recordId=50000000-0000-4000-8000-000000000001')
  await page.screenshot({ path: '../../test-results/assistant-source-evidence/source-card.png', fullPage: true })
  await page.evaluate(() => { window.__guanxianPreview.sourceUnavailable = true })
  await directory.getByRole('button', { name: '重新核对当前资料' }).click()
  await expect(directory).toContainText('资料已不可用或当前身份无权查看')
  await expect(directory).not.toContainText('重新读取的虚构公开资料')
})

test('activity evidence stays distinct and small-screen source cards remain inside the chat', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/tests/ui/assistant-preview.html')
  await page.getByRole('button', { name: '打开管线智能助手' }).click()
  await page.getByLabel('向管线智能助手提问').fill('查询企业活动')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.locator('.query-receipt summary')).toContainText('企业活动与公开动态查询')
  await expect(page.locator('.business-card')).toContainText('不代表仍可投标或平台确认合作')
  const box = (await page.locator('.business-card').boundingBox())!
  expect(box.x).toBeGreaterThanOrEqual(0); expect(box.x + box.width).toBeLessThanOrEqual(390)
  await page.getByRole('button', { name: '重新核对资料', exact: true }).click()
  const sourceCard = page.locator('.source-directory .source-card')
  await expect(sourceCard).toContainText('重新读取的虚构公开资料')
  const expanded = (await sourceCard.boundingBox())!
  expect(expanded.x).toBeGreaterThanOrEqual(0); expect(expanded.x + expanded.width).toBeLessThanOrEqual(390)
  await page.screenshot({ path: '../../test-results/assistant-source-evidence/activity-mobile.png', fullPage: true })
})
