import { expect, test, type Page } from '@playwright/test'

const authority = 'http://127.0.0.1:18188/identity/realms/entry-tests'
const storageKey = `oidc.user:${authority}:entry-tests`
const associationId = '10000000-0000-4000-8000-000000000001'

// Real oidc-client-ts + Vue + HTTP gate, synthetic provider/backend only.
async function fixture(page: Page, lifetime = 120) {
  const observations = { renewals: 0, grants: [] as string[], apiTokens: [] as string[], writes: [] as string[], errors: [] as string[], failure: 0, role: 'ASSOCIATION_ADMIN' }
  page.on('pageerror', error => observations.errors.push(error.message))
  await page.clock.install()
  await page.addInitScript(({ storageKey, lifetime }) => {
    if (!sessionStorage.getItem(storageKey)) sessionStorage.setItem(storageKey, JSON.stringify({
      access_token: 'synthetic-access-0', refresh_token: 'synthetic-refresh-0', token_type: 'Bearer', scope: 'openid',
      expires_at: Math.floor(Date.now() / 1000) + lifetime, profile: { sub: 'session-ui-user' },
    }))
  }, { storageKey, lifetime })
  await page.route('**/identity/realms/entry-tests/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/.well-known/openid-configuration')) return route.fulfill({ json: {
      issuer: authority, token_endpoint: `${authority}/protocol/openid-connect/token`,
      authorization_endpoint: `${authority}/protocol/openid-connect/auth`, jwks_uri: `${authority}/protocol/openid-connect/certs`,
    } })
    expect(path).toContain('/protocol/openid-connect/token')
    observations.renewals++
    const body = new URLSearchParams(route.request().postData() || '')
    expect(body.get('grant_type')).toBe('refresh_token')
    expect(body.get('client_id')).toBe('entry-tests')
    observations.grants.push(body.get('refresh_token') || '')
    if (observations.failure) return route.fulfill({ status: observations.failure, json: {
      error: observations.failure === 400 ? 'invalid_grant' : 'temporarily_unavailable',
    } })
    return route.fulfill({ json: {
      access_token: `synthetic-access-${observations.renewals}`, refresh_token: `synthetic-refresh-${observations.renewals}`,
      token_type: 'Bearer', scope: 'openid', expires_in: 300,
    } })
  })
  await page.route('**/api/v1/**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/health') return route.fulfill({ json: { code: 'OK', data: { status: 'UP' } } })
    observations.apiTokens.push(request.headers()['authorization'] || '')
    if (request.method() !== 'GET') observations.writes.push(`${request.method()} ${path}`)
    const fulfill = (data: unknown) => route.fulfill({ json: { code: 'OK', data } })
    if (path === '/api/v1/users/me') return fulfill({
      subject: 'session-ui-user', username: 'session.fixture', displayName: '会话验证账号', organization: '虚构·会话验证协会',
      title: observations.role, roles: [observations.role], permissions: ['MEMBER_READ', 'POLICY_READ'], associationId, enterpriseId: null,
    })
    if (path.endsWith('/dashboards/association')) return fulfill({ metrics: [], activities: [], sceneDistribution: [], pendingTasks: [] })
    if (path.includes('notifications') || path.endsWith('/members/page')) return fulfill({ items: [], total: 0, page: 0, size: 20 })
    if (path.includes('/system-context/') || path.endsWith('/members')) return fulfill([])
    return route.fulfill({ status: 403, json: { code: 'FORBIDDEN', message: 'Outside synthetic session fixture' } })
  })
  await page.goto('/association')
  await expect(page.getByRole('heading', { name: '从一个问题开始' })).toBeVisible()
  return observations
}

test('renews before expiry with rotated refresh tokens, preserving navigation and unsent draft', async ({ page }, info) => {
  const observed = await fixture(page)
  const draft = page.getByLabel('向管线智能助手提问')
  await draft.fill('闲置后仍应保留的草稿，不要发送')
  await page.clock.fastForward(70000)
  await expect.poll(() => observed.renewals).toBe(1)
  await expect.poll(() => observed.apiTokens.at(-1)).toBe('Bearer synthetic-access-1')
  await expect(page.locator('.main-nav').getByRole('link', { name: '会员企业', exact: true })).toBeVisible()
  await expect(draft).toHaveValue('闲置后仍应保留的草稿，不要发送')
  await page.clock.fastForward(250000)
  await expect.poll(() => observed.renewals).toBe(2)
  expect(observed.grants).toEqual(['synthetic-refresh-0', 'synthetic-refresh-1'])
  await page.locator('.main-nav').getByRole('link', { name: '会员企业', exact: true }).click()
  await expect(page.getByRole('heading', { name: '会员企业', exact: true })).toBeVisible()
  await expect.poll(() => observed.apiTokens.at(-1)).toBe('Bearer synthetic-access-2')
  expect(observed.writes).toEqual([])
  expect(observed.errors).toEqual([])
  await page.screenshot({ path: info.outputPath('renewed-members.png'), fullPage: true })
})

test('recovers after sleeping past token expiry with suspended timers and simultaneous wake signals', async ({ page }) => {
  const observed = await fixture(page)
  await page.getByLabel('向管线智能助手提问').fill('休眠前草稿')
  // setSystemTime advances wall time without firing timers: a throttled background tab.
  const now = await page.evaluate(() => Date.now())
  await page.clock.setSystemTime(now + 20 * 60 * 1000)
  await page.evaluate(() => {
    window.dispatchEvent(new Event('focus'))
    window.dispatchEvent(new Event('online'))
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await expect.poll(() => observed.apiTokens.at(-1)).toBe('Bearer synthetic-access-1')
  expect(observed.renewals).toBe(1)
  await expect(page).toHaveURL(/\/association$/)
  await expect(page.getByLabel('向管线智能助手提问')).toHaveValue('休眠前草稿')
  await expect(page.locator('.main-nav a')).not.toHaveCount(0)
  expect(observed.errors).toEqual([])
})

test('temporary provider failure preserves the mounted workspace; retry restores it without resubmitting', async ({ page }, info) => {
  const observed = await fixture(page)
  await page.getByLabel('向管线智能助手提问').fill('断网时不能丢失的草稿')
  observed.failure = 503
  await page.clock.fastForward(70000)
  const recovery = page.getByRole('dialog', { name: '连接暂时中断' })
  await expect(recovery).toBeVisible()
  await expect(page.locator('.app-shell')).toHaveAttribute('inert', '')
  expect(await page.locator('.main-nav a').count()).toBeGreaterThan(0)
  await expect(page).toHaveURL(/\/association$/)
  await page.keyboard.press('Escape')
  await expect(recovery).toBeVisible()
  await page.screenshot({ path: info.outputPath('session-recovery-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 320, height: 740 })
  await page.clock.runFor(400)
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320)
  await page.screenshot({ path: info.outputPath('session-recovery-mobile.png'), fullPage: true })
  observed.failure = 0
  await recovery.getByRole('button', { name: '重试连接' }).click()
  await expect(recovery).not.toBeVisible()
  await expect(page.locator('.app-shell')).not.toHaveAttribute('inert', '')
  await expect(page.getByLabel('向管线智能助手提问')).toHaveValue('断网时不能丢失的草稿')
  expect(observed.writes).toEqual([])
  expect(observed.errors).toEqual([])
})

test('a revoked refresh token returns to login with an explanation and no empty private shell', async ({ page }) => {
  const observed = await fixture(page)
  observed.failure = 400
  await page.clock.fastForward(70000)
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await expect(page.getByRole('alert')).toContainText('登录会话已到期或被撤销')
  await expect(page.locator('.app-shell')).toHaveCount(0)
  expect(await page.evaluate(key => sessionStorage.getItem(key), storageKey)).toBeNull()
  expect(observed.writes).toEqual([])
})

test('renewal applies a downgraded backend role and leaves an unauthorized workspace', async ({ page }) => {
  const observed = await fixture(page)
  observed.role = 'OBSERVER'
  await page.clock.fastForward(70000)
  await expect(page).toHaveURL(/\/members$/)
  await expect(page.locator('.main-nav').getByRole('link', { name: '协会工作台', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '新增企业', exact: true })).toHaveCount(0)
  expect(observed.errors).toEqual([])
})
