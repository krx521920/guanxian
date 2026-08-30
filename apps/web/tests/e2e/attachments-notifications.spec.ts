import { expect, test } from '@playwright/test'
import { authenticatedPage, e2eUsers, waitForApiWrite } from './support'

test.describe('真实附件与通知闭环', () => {
  test('企业管理员经 Redis 限流写入 MinIO，并完成下载、软删除与恢复', async ({ browser }) => {
    const { context, page } = await authenticatedPage(browser, e2eUsers.enterpriseAdmin)
    const filename = `e2e-attachment-${Date.now()}.txt`
    const content = Buffer.from('管线智联平台 Playwright 真实 MinIO 附件闭环。\n', 'utf8')
    try {
      await page.goto('/attachments')
      await expect(page.getByRole('heading', { name: '资料附件' })).toBeVisible()

      await waitForApiWrite(page, /\/api\/v1\/attachments$/, async () => {
        await page.locator('input[type="file"]').setInputFiles({
          name: filename,
          mimeType: 'text/plain',
          buffer: content,
        })
      })
      await expect(page.getByText(`已上传 ${filename}`, { exact: false })).toBeVisible()
      const row = page.locator('tr').filter({ hasText: filename })
      await expect(row).toContainText('有效')

      const downloadPromise = page.waitForEvent('download')
      await row.getByRole('button', { name: '下载' }).click()
      const download = await downloadPromise
      expect(download.suggestedFilename()).toBe(filename)
      const stream = await download.createReadStream()
      const chunks: Buffer[] = []
      for await (const chunk of stream) chunks.push(Buffer.from(chunk))
      expect(Buffer.concat(chunks)).toEqual(content)

      await waitForApiWrite(page, /\/api\/v1\/attachments\/[0-9a-f-]+$/, async () => {
        await row.getByRole('button', { name: '删除' }).click()
      })
      await expect(row).toContainText('已删除')

      await waitForApiWrite(page, /\/api\/v1\/attachments\/[0-9a-f-]+\/restore$/, async () => {
        await row.getByRole('button', { name: '恢复' }).click()
      })
      await expect(row).toContainText('有效')
    } finally {
      await context.close()
    }
  })

  test('协会管理员可以读取真实通知并标记已读', async ({ browser }) => {
    const { context, page } = await authenticatedPage(browser, e2eUsers.associationAdmin)
    try {
      await page.getByRole('button', { name: '消息通知' }).click()
      const panel = page.getByRole('region', { name: '消息通知' })
      await expect(panel).toContainText('E2E 会员资料待核验')
      await expect(panel).toContainText('1 条未读')
      const item = panel.getByRole('button').filter({ hasText: 'E2E 会员资料待核验' })
      await expect(item).toHaveClass(/unread/)

      await waitForApiWrite(page, /\/api\/v1\/notifications\/messages\/[0-9a-f-]+\/read$/, async () => {
        await item.click()
      })
      await expect(page).toHaveURL(/\/members(?:[/?#]|$)/)
      await page.getByRole('button', { name: '消息通知' }).click()
      await expect(page.getByRole('region', { name: '消息通知' })).toContainText('暂无未读')
    } finally {
      await context.close()
    }
  })
})
