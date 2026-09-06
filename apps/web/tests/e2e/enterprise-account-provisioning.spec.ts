import { randomUUID } from 'node:crypto'
import { expect, test, type Page } from '@playwright/test'

// Writes only into the disposable Compose E2E realm/database. No route mocks.
test('真实认证：管理员直接开户、首次改密、我的企业、自助改密和重置会话失效', async ({ browser, request, baseURL }) => {
  expect(new URL(baseURL!).origin).toBe('http://127.0.0.1:18082')
  const tokenResponse = await request.post('http://127.0.0.1:18081/realms/guanxian-ci/protocol/openid-connect/token', {
    form: { grant_type: 'password', client_id: 'guanxian-load', username: 'ci-load-admin', password: 'ci-only-user-password' },
  })
  expect(tokenResponse.status()).toBe(200)
  const adminToken = (await tokenResponse.json()).access_token
  const headers = { Authorization: `Bearer ${adminToken}`, 'X-Guanxian-Association-Id': '00000000-0000-0000-0000-000000000106' }
  const username = 'ci_owner_' + randomUUID().replaceAll('-', '')
  const createdEnterprise = await request.post('/api/v1/members', { headers, data: {
    name: 'CI虚构开户企业-' + username, category: '技术服务', unifiedSocialCreditCode: null,
    address: null, contactName: null, contactPhone: null, introduction: '隔离开户验收',
    capabilities: [], products: [], cooperationNeeds: [], visibility: 'PRIVATE', status: 'INCOMPLETE',
  } })
  expect(createdEnterprise.status()).toBe(201)
  const enterprise = (await createdEnterprise.json()).data
  const path = `/api/v1/enterprise-accounts/enterprises/${enterprise.id}`
  expect((await request.get(path)).status()).toBe(401)
  const statusResponse = await request.get(path, { headers })
  expect(statusResponse.status()).toBe(200)
  const status = (await statusResponse.json()).data
  expect(status.enabled).toBe(true)
  const accountResponse = await request.post(path, { headers: { ...headers, 'If-Match': `"${status.enterpriseVersion}"` },
    data: { username, note: 'CI隔离环境：已核验虚构企业负责人', confirmed: true } })
  expect(accountResponse.status()).toBe(200)
  expect(accountResponse.headers()['cache-control']).toBe('no-store')
  const account = (await accountResponse.json()).data
  expect(account.account.status).toBe('ACTIVE')
  expect(account.temporaryPassword.length).toBeGreaterThanOrEqual(32)
  expect((await (await request.get(path, { headers })).json()).data).not.toHaveProperty('temporaryPassword')

  const ownerContext = await browser.newContext(), replacementContext = await browser.newContext()
  try {
    const page = await ownerContext.newPage()
    await login(page, username, account.temporaryPassword)
    await changeRequiredPassword(page, 'Ci-Only!OwnerChosenPassword2026')
    await expect(page).toHaveURL(/\/my-enterprise$/)
    await expect(page.getByRole('heading', { name: '我的企业', exact: true })).toBeVisible()
    const identity = await sessionApi(page, '/users/me')
    expect(identity.status).toBe(200); expect(identity.data.enterpriseId).toBe(enterprise.id)
    expect(identity.data.roles).toEqual(['ENTERPRISE_ADMIN'])
    expect((await sessionApi(page, path.replace('/api/v1', ''))).status).toBe(403)
    expect((await sessionApi(page, '/my-enterprise')).data.profile.id).toBe(enterprise.id)
    expect((await request.get(`/api/v1/public/enterprises/${enterprise.id}`)).status()).toBe(404)

    await page.getByRole('button', { name: '当前账号菜单' }).click()
    await page.getByRole('button', { name: '修改密码', exact: true }).click()
    await expect(page.locator('#password')).toBeVisible()
    await page.locator('#password').fill('Ci-Only!OwnerChosenPassword2026')
    await page.locator('#kc-login').click()
    await changeRequiredPassword(page, 'Ci-Only!OwnerUpdatedPassword2026')
    await expect(page).toHaveURL(/\/my-enterprise$/)
    expect((await sessionApi(page, '/my-enterprise')).status).toBe(200)

    const reset = await request.post(path + '/reset-password', {
      headers: { ...headers, 'If-Match': `"${account.account.version}"` },
      data: { note: 'CI：核验接收人后的忘记密码重置', confirmed: true },
    })
    expect(reset.status()).toBe(200); expect(reset.headers()['cache-control']).toBe('no-store')
    const resetResult = (await reset.json()).data
    expect(resetResult.temporaryPassword).not.toBe(account.temporaryPassword)
    expect((await sessionApi(page, '/my-enterprise')).status).toBe(401)
    const replacement = await replacementContext.newPage()
    await login(replacement, username, resetResult.temporaryPassword)
    await changeRequiredPassword(replacement, 'Ci-Only!RecoveredOwnerPassword2026')
    await expect(replacement).toHaveURL(/\/my-enterprise$/)
    expect((await sessionApi(replacement, '/my-enterprise')).data.profile.id).toBe(enterprise.id)
  } finally { await Promise.all([ownerContext.close(), replacementContext.close()]) }
})

async function login(page: Page, username: string, password: string) {
  await page.goto('/login?entry=enterprise')
  await page.getByRole('button', { name: /统一身份登录/ }).click()
  await expect(page.locator('#username')).toBeVisible()
  await page.locator('#username').fill(username); await page.locator('#password').fill(password)
  await page.locator('#kc-login').click()
}
async function changeRequiredPassword(page: Page, password: string) {
  await expect(page.locator('#password-new')).toBeVisible()
  await page.locator('#password-new').fill(password); await page.locator('#password-confirm').fill(password)
  await page.locator('#kc-passwd-update-form input[name="login"][type="submit"]').click()
}
async function sessionApi(page: Page, path: string): Promise<{ status: number; data: any }> {
  return page.evaluate(async path => {
    const key = Object.keys(sessionStorage).find(key => key.startsWith('oidc.user:'))
    if (!key) throw new Error('Missing real OIDC session')
    const token = JSON.parse(sessionStorage.getItem(key)!).access_token
    const response = await fetch('/api/v1' + path, { cache: 'no-store', headers: { Authorization: `Bearer ${token}` } })
    const payload = await response.json()
    return { status: response.status, data: payload.data }
  }, path)
}
