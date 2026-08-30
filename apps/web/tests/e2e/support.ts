import { expect, type Browser, type BrowserContext, type Page } from '@playwright/test'

export interface E2eUser {
  username: string
  password: string
  displayName: string
  roleLabel: string
}

export const e2eUsers = {
  associationAdmin: {
    username: 'ci-association-admin',
    password: 'ci-only-user-password',
    displayName: 'CI 协会管理员',
    roleLabel: '协会管理员',
  },
  enterpriseAdmin: {
    username: 'ci-enterprise-admin',
    password: 'ci-only-user-password',
    displayName: 'CI 企业管理员',
    roleLabel: '企业管理员',
  },
  supplierAdmin: {
    username: 'ci-supplier-admin',
    password: 'ci-only-user-password',
    displayName: 'CI 供给方管理员',
    roleLabel: '企业管理员',
  },
  marketManager: {
    username: 'ci-enterprise-member',
    password: 'ci-only-user-password',
    displayName: 'CI 市场经理',
    roleLabel: '企业业务人员',
  },
} satisfies Record<string, E2eUser>

export interface AuthenticatedPage {
  context: BrowserContext
  page: Page
}

export async function authenticatedPage(
  browser: Browser,
  user: E2eUser,
): Promise<AuthenticatedPage> {
  const context = await browser.newContext()
  const page = await context.newPage()
  await loginThroughOidc(page, user)
  return { context, page }
}

export async function loginThroughOidc(page: Page, user: E2eUser): Promise<void> {
  const applicationOrigin = new URL(
    process.env.E2E_WEB_BASE_URL?.trim() || 'http://127.0.0.1:18082',
  ).origin
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: '欢迎使用管理协作平台' })).toBeVisible()

  await page.getByRole('button', { name: /统一身份登录/ }).click()
  await page.waitForURL((url) => url.pathname.includes('/realms/guanxian-ci/'))
  await page.locator('#username').fill(user.username)
  await page.locator('#password').fill(user.password)
  await page.locator('#kc-login').click()

  await page.waitForURL((url) => url.origin === applicationOrigin
    && !url.pathname.startsWith('/auth/callback'))
  await expect(page.locator('.profile-copy strong')).toHaveText(user.displayName)
  await expect(page.locator('.sidebar-context')).toContainText(user.roleLabel)
  await expect(page).not.toHaveURL(/\/auth\/callback(?:[/?#]|$)/)
}

export function assetCard(page: Page, name: string) {
  return page.locator('article.asset-card').filter({ hasText: name })
}

export function matchCard(page: Page, demandTitle: string, supplierName?: string) {
  let card = page.locator('article.match-card').filter({ hasText: demandTitle })
  if (supplierName) card = card.filter({ hasText: supplierName })
  return card
}

export async function openMatch(
  page: Page,
  demandTitle: string,
  supplierName?: string,
): Promise<void> {
  await page.goto('/matching')
  const card = matchCard(page, demandTitle, supplierName)
  await expect(card).toHaveCount(1)
  await card.getByRole('button', { name: '查看匹配详情' }).click()
  await expect(page.locator('.match-detail-modal')).toBeVisible()
}

export async function waitForApiWrite(
  page: Page,
  path: RegExp,
  action: () => Promise<void>,
): Promise<void> {
  const responsePromise = page.waitForResponse((response) => {
    const request = response.request()
    return path.test(new URL(response.url()).pathname)
      && !['GET', 'HEAD', 'OPTIONS'].includes(request.method())
  })
  await action()
  const response = await responsePromise
  const responseBody = await response.text()
  expect(response.status(), responseBody).toBeGreaterThanOrEqual(200)
  expect(response.status(), responseBody).toBeLessThan(300)
}
