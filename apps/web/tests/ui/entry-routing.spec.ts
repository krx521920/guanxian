import { expect, test, type Page } from '@playwright/test'
import type { UserRole } from '../../src/types/domain'

const associationId = '10000000-0000-4000-8000-000000000001'
const enterpriseId = '20000000-0000-4000-8000-000000000001'
const authority = 'http://127.0.0.1:18188/identity/realms/entry-tests'

async function identityFixture(page: Page, role?: UserRole, options: { unbound?: boolean; rejected?: boolean; expired?: boolean } = {}) {
  const requests: string[] = []
  const pageErrors: string[] = []
  page.on('pageerror', error => pageErrors.push(error.message))
  await page.addInitScript(({ role, authority, expired }) => {
    // A forged demo flag must not determine identity in OIDC mode.
    sessionStorage.setItem('guanxian.demo.role', 'SYSTEM_ADMIN')
    if (role) sessionStorage.setItem(`oidc.user:${authority}:entry-tests`, JSON.stringify({
      access_token: 'local-ui-test-token-not-a-real-credential', token_type: 'Bearer', scope: 'openid',
      expires_at: Math.floor(Date.now() / 1000) + (expired ? -30 : 3600),
      profile: { sub: 'ui-test-user', roles: ['SYSTEM_ADMIN'] },
    }))
  }, { role, authority, expired: options.expired })
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    requests.push(path)
    const fulfill = (data: unknown) => route.fulfill({ json: { code: 'OK', data } })
    if (path === '/api/v1/public/enterprises') {
      expect(route.request().headers()['authorization']).toBeUndefined()
      return fulfill([])
    }
    if (path === '/api/v1/users/me') {
      if (options.rejected) return route.fulfill({ status: 403, json: { code: 'FORBIDDEN', message: 'Test account is not bound' } })
      return fulfill({ subject: 'ui-test-user', username: 'ui.fixture', displayName: '本地验证账号',
        organization: role?.startsWith('ENTERPRISE_') ? '虚构·入口验证企业' : '虚构·入口验证协会',
        title: role, roles: [role], permissions: ['MEMBER_READ', 'POLICY_READ'], associationId,
        enterpriseId: role?.startsWith('ENTERPRISE_') && !options.unbound ? enterpriseId : null })
    }
    if (path === '/api/v1/dashboards/enterprise') return fulfill({ completeness: 75, metrics: [], matches: [], recommendedPolicies: [] })
    if (path === '/api/v1/dashboards/association') return fulfill({ metrics: [], activities: [], sceneDistribution: [], pendingTasks: [] })
    if (path.includes('notifications') || path.endsWith('/members/page')) return fulfill({ items: [], total: 0, page: 0, size: 20 })
    if (path.includes('/system-context/')) return fulfill([])
    // Fail closed for APIs outside the fixture; never proxy requests to a live service.
    return route.fulfill({ status: 403, json: { code: 'FORBIDDEN', message: 'Outside local UI fixture' } })
  })
  return { requests, pageErrors }
}

test('anonymous entry has three working paths, and no role selector in OIDC mode', async ({ page }, info) => {
  const { requests, pageErrors } = await identityFixture(page)
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '欢迎来到管线智联' })).toBeVisible()
  await expect(page.getByRole('button', { name: '企业登录' })).toBeVisible()
  await expect(page.getByRole('button', { name: '管理员登录' })).toBeVisible()
  await expect(page.getByRole('combobox')).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('unified-entry.png'), fullPage: true })
  await page.getByRole('button', { name: '企业登录' }).click()
  await expect(page.getByRole('heading', { name: '企业账号登录' })).toBeVisible()
  await expect(page.getByRole('button', { name: '继续统一身份登录' })).toBeVisible()
  await expect(page.getByRole('combobox')).toHaveCount(0)
  await page.getByRole('button', { name: '返回入口选择' }).click()
  await page.getByRole('button', { name: '管理员登录' }).click()
  await expect(page.getByRole('heading', { name: '管理员账号登录' })).toBeVisible()
  await page.getByRole('link', { name: '游客浏览' }).click()
  await expect(page).toHaveURL(/\/public$/)
  await expect(page.getByRole('heading', { name: '暂无符合条件的已发布企业' })).toBeVisible()
  expect(requests).toEqual(['/api/v1/public/enterprises'])
  expect(pageErrors).toEqual([])
})

test('public browsing never initializes a stored private identity or renders the internal shell', async ({ page }, info) => {
  const { requests, pageErrors } = await identityFixture(page, 'SYSTEM_ADMIN')
  await page.goto('/public')
  await expect(page.getByRole('heading', { name: '企业公开目录' })).toBeVisible()
  await expect(page.locator('.app-shell, .assistant-launcher, .chat-assistant')).toHaveCount(0)
  await expect(page.getByText('本地验证账号')).toHaveCount(0)
  await page.getByRole('link', { name: '企业展示', exact: true }).click()
  await expect(page).toHaveURL(/#public-directory$/)
  await page.screenshot({ path: info.outputPath('public-portal.png'), fullPage: true })
  expect(requests).toEqual(['/api/v1/public/enterprises'])
  expect(pageErrors).toEqual([])
})

for (const [role, home, title] of [
  ['SYSTEM_ADMIN', '/association', '协会工作台'],
  ['ASSOCIATION_ADMIN', '/association', '协会工作台'],
  ['ASSOCIATION_OPERATOR', '/association', '协会工作台'],
  ['ENTERPRISE_ADMIN', '/enterprise', '企业工作台'],
  ['ENTERPRISE_MEMBER', '/enterprise', '企业工作台'],
  ['OBSERVER', '/members', '会员企业'],
] as const) {
  test(`${role} enters its verified workspace even through the opposite entry`, async ({ page }) => {
    const { requests, pageErrors } = await identityFixture(page, role)
    await page.goto(`/login?entry=${role.startsWith('ENTERPRISE_') ? 'admin' : 'enterprise'}`)
    await expect(page).toHaveURL(new RegExp(`${home}$`))
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
    expect(requests[0]).toBe('/api/v1/users/me')
    if (role.startsWith('ENTERPRISE_')) {
      await expect(page.getByRole('note')).toContainText('虚构·入口验证企业')
      await expect(page.locator('.main-nav').getByRole('link', { name: '审计与账号' })).toHaveCount(0)
      expect(requests).not.toContain('/api/v1/dashboards/association')
      if (role === 'ENTERPRISE_ADMIN') {
        await expect(page.getByRole('link', { name: '维护本企业资料', exact: true })).toHaveAttribute('href', '/enterprise/profile')
      } else {
        await expect(page.getByRole('link', { name: '维护本企业资料', exact: true })).toHaveCount(0)
        await expect(page.getByRole('note')).toContainText('只读身份')
      }
    }
    expect(pageErrors).toEqual([])
  })
}

test('enterprise cannot route into management or another enterprise editor', async ({ page }) => {
  const { requests } = await identityFixture(page, 'ENTERPRISE_ADMIN')
  await page.goto('/login?entry=admin&redirect=/operations')
  await expect(page).toHaveURL(/\/enterprise$/)
  await page.goto('/members/other-enterprise/edit')
  await expect(page).toHaveURL(/\/enterprise$/)
  expect(requests).not.toContain('/api/v1/members/other-enterprise')
  expect(requests).not.toContain('/api/v1/access-bindings')
})

test('unbound identity shows actionable help without requesting business data', async ({ page }) => {
  const { requests, pageErrors } = await identityFixture(page, 'ENTERPRISE_ADMIN', { unbound: true })
  await page.goto('/')
  await expect(page).toHaveURL(/\/access-help$/)
  await expect(page.getByRole('heading', { name: '账号尚未完成组织绑定' })).toBeVisible()
  await expect(page.getByRole('button', { name: '重新检查绑定' })).toBeVisible()
  await expect(page.locator('.app-shell')).toHaveCount(0)
  expect(requests).toEqual(['/api/v1/users/me'])
  await page.getByRole('link', { name: '先浏览公开页面' }).click()
  await expect(page).toHaveURL(/\/public$/)
  expect(pageErrors).toEqual([])
})

test('expired identity cannot enter a business page, but can browse publicly', async ({ page }) => {
  const { requests } = await identityFixture(page, 'ENTERPRISE_ADMIN', { expired: true })
  await page.goto('/enterprise')
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await expect(page.locator('.app-shell')).toHaveCount(0)
  expect(requests).toEqual([])
  await page.getByRole('link', { name: '游客浏览' }).click()
  await expect(page).toHaveURL(/\/public$/)
})

test('backend identity rejection is explained on login without mounting the shell', async ({ page }) => {
  const { requests } = await identityFixture(page, 'ENTERPRISE_ADMIN', { rejected: true })
  await page.goto('/enterprise')
  await expect(page.getByRole('alert')).toContainText('账号没有平台访问权限，或尚未完成组织绑定')
  await expect(page.locator('.app-shell')).toHaveCount(0)
  expect(requests).toEqual(['/api/v1/users/me', '/api/v1/onboarding/session'])
  await page.getByRole('link', { name: '游客浏览' }).click()
  await expect(page).toHaveURL(/\/public$/)
})

test('existing return control and avatar menu still work, including public navigation', async ({ page }) => {
  await identityFixture(page, 'ENTERPRISE_MEMBER')
  await page.goto('/')
  await expect(page.getByRole('link', { name: '返回工作台' })).toHaveAttribute('href', '/enterprise')
  await page.getByRole('button', { name: '当前账号菜单' }).click()
  await expect(page.getByRole('button', { name: '界面设置' })).toBeVisible()
  await page.getByRole('link', { name: '浏览公开页面' }).click()
  await expect(page).toHaveURL(/\/public$/)
  await expect(page.locator('.app-shell')).toHaveCount(0)
  await page.getByRole('link', { name: '登录工作空间' }).click()
  await expect(page).toHaveURL(/\/enterprise$/)
})

test('mobile entry and public portal fit the viewport with reachable navigation', async ({ page }, info) => {
  await identityFixture(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await expect(page.getByRole('button', { name: '企业登录' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.screenshot({ path: info.outputPath('entry-mobile.png'), fullPage: true })
  await page.getByRole('link', { name: '游客浏览' }).click()
  await expect(page.getByRole('heading', { name: '暂无符合条件的已发布企业' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.screenshot({ path: info.outputPath('public-mobile.png'), fullPage: true })
  await page.getByRole('link', { name: '返回统一入口' }).click()
  await expect(page.getByRole('heading', { name: '欢迎来到管线智联' })).toBeVisible()
})

test('both account entry buttons start the same authorization-code flow without granting a role', async ({ page }) => {
  await identityFixture(page)
  await page.route(`${authority}/.well-known/openid-configuration`, route => route.fulfill({ json: {
    issuer: authority,
    authorization_endpoint: `${authority}/protocol/openid-connect/auth`,
    token_endpoint: `${authority}/protocol/openid-connect/token`,
    jwks_uri: `${authority}/protocol/openid-connect/certs`,
    response_types_supported: ['code'],
    subject_types_supported: ['public'],
    id_token_signing_alg_values_supported: ['RS256'],
  } }))
  await page.route(`${authority}/protocol/openid-connect/auth?*`, route => route.fulfill({
    contentType: 'text/html; charset=utf-8', body: '<!doctype html><meta charset="utf-8"><h1>本地模拟统一认证页</h1>',
  }))
  for (const entry of ['enterprise', 'admin']) {
    await page.goto(`/login?entry=${entry}`)
    await page.getByRole('button', { name: '继续统一身份登录' }).click()
    await expect(page.getByRole('heading', { name: '本地模拟统一认证页' })).toBeVisible()
    const target = new URL(page.url())
    expect(target.origin).toBe('http://127.0.0.1:18188')
    expect(target.searchParams.get('client_id')).toBe('entry-tests')
    expect(target.searchParams.get('redirect_uri')).toBe('http://127.0.0.1:18188/auth/callback')
    expect(target.searchParams.get('response_type')).toBe('code')
    expect(target.searchParams.get('code_challenge_method')).toBe('S256')
    expect(target.searchParams.get('code_challenge')).toBeTruthy()
    expect(target.searchParams.has('role')).toBe(false)
  }
})
