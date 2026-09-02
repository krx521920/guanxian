import { expect, test } from '@playwright/test'
import { authenticatedPage, e2eUsers } from './support'

test.describe('真实 OIDC 身份与界面权限', () => {
  test('协会管理员进入协会范围，企业写入入口不会错误开放', async ({ browser }) => {
    const { context, page } = await authenticatedPage(browser, e2eUsers.associationAdmin)
    try {
      await expect(page).toHaveURL(/\/association(?:[/?#]|$)/)
      await expect(page.getByRole('link', { name: '友好协会' })).toBeVisible()
      await page.goto('/ecosystem')
      await expect(page.getByRole('heading', { name: '产业生态资产' })).toBeVisible()
      await expect(page.getByRole('button', { name: '+ 发布需求' })).toHaveCount(0)
      await expect(page.getByRole('button', { name: '+ 新建产品/服务' })).toHaveCount(0)
    } finally {
      await context.close()
    }
  })

  test('企业管理员可以维护本企业，市场经理保持只读且不能进入协会管理页', async ({ browser }) => {
    const admin = await authenticatedPage(browser, e2eUsers.enterpriseAdmin)
    const manager = await authenticatedPage(browser, e2eUsers.marketManager)
    try {
      await admin.page.goto('/ecosystem')
      await expect(admin.page.getByRole('button', { name: '+ 发布需求' })).toBeVisible()
      await expect(admin.page.getByRole('button', { name: '+ 新建产品/服务' })).toBeVisible()

      await manager.page.goto('/ecosystem')
      await expect(manager.page.getByRole('heading', { name: '产业生态资产' })).toBeVisible()
      await expect(manager.page.getByRole('button', { name: '+ 发布需求' })).toHaveCount(0)
      await expect(manager.page.getByRole('button', { name: '+ 新建产品/服务' })).toHaveCount(0)
      await manager.page.goto('/attachments')
      await expect(manager.page.getByRole('button', { name: '+ 上传附件' })).toHaveCount(0)

      await manager.page.goto('/federation')
      await expect(manager.page).toHaveURL(/\/enterprise(?:[/?#]|$)/)
    } finally {
      await Promise.all([admin.context.close(), manager.context.close()])
    }
  })
})
