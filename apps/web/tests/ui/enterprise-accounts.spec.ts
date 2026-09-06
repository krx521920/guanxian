import { expect, test, type Page } from '@playwright/test'
const associationId = '61000000-0000-4000-8000-000000000001', enterpriseId = '62000000-0000-4000-8000-000000000001'
const authority = 'http://127.0.0.1:18188/identity/realms/entry-tests'
const temporaryPassword = 'Fixture-Only!NotARealPassword2026'
async function fixture(page: Page, role = 'SYSTEM_ADMIN') {
  const account = { enterpriseId, enterpriseName: '虚构·开户验证企业', enterpriseVersion: 7, enabled: true, existingBinding: false, username: 'ent_fixture', status: 'NOT_CREATED', version: 0 }
  const state = { account, writes: [] as { path: string; body: Record<string, unknown> }[], interrupted: false, conflict: false, errors: [] as string[] }
  page.on('pageerror', e => state.errors.push(e.message))
  await page.addInitScript(({ authority, associationId }) => {
    sessionStorage.setItem('guanxian.system.context', JSON.stringify({ associationId, enterpriseId: null }))
    sessionStorage.setItem(`oidc.user:${authority}:entry-tests`, JSON.stringify({ access_token: 'fixture-token', token_type: 'Bearer', scope: 'openid', expires_at: Math.floor(Date.now()/1000)+3600, profile: { sub: 'fixture-user' } }))
  }, { authority, associationId })
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    const ok = (data: unknown) => route.fulfill({ json: { code: 'OK', data } })
    const fail = (status: number, message: string) => route.fulfill({ status, json: { code: 'FIXTURE_ERROR', message } })
    if (path.endsWith('/users/me')) return ok({ subject: 'fixture-user', username: 'fixture.user', displayName: '测试管理员', roles: [role], permissions: [], associationId, enterpriseId: role === 'ENTERPRISE_MEMBER' ? enterpriseId : null, organization: '虚构协会' })
    if (path.endsWith('/system-context/associations')) return ok([{ id: associationId, name: '虚构协会' }])
    if (path.includes('/system-context/')) return ok([])
    if (path.endsWith('/members/page')) return ok({ items: [{ id: enterpriseId, name: account.enterpriseName, shortName: '虚构企业', role: '技术服务', scenes: [], products: [], completeness: 80, status: '待审核', canEdit: role === 'SYSTEM_ADMIN', canReview: role === 'SYSTEM_ADMIN', version: 7, updatedAt: '2026-09-05T12:00:00Z' }], total: 1, page: 0, size: 20 })
    if (path.includes('/enterprise-accounts/')) {
      if (role !== 'SYSTEM_ADMIN') return fail(403, 'Not an administrator')
      if (request.method() === 'GET') return ok(account)
      const body = request.postDataJSON(); state.writes.push({ path, body })
      expect(body.confirmed).toBe(true); expect(body).not.toHaveProperty('password'); expect(body).not.toHaveProperty('role')
      if (path.endsWith('/reset-password')) {
        expect(request.headers()['if-match']).toBe(`"${account.version}"`)
        if (state.conflict) { account.version++; return fail(412, '账号版本已更新，请刷新后重试') }
        account.version += 2
      } else if (path.endsWith('/resume')) {
        expect(request.headers()['if-match']).toBe(`"${account.version}"`); account.status = 'ACTIVE'; account.version++
      } else {
        expect(request.headers()['if-match']).toBe('"7"'); account.username = body.username
        if (state.interrupted) { account.status = 'CREATING'; account.version = 1; return fail(502, '认证系统未完成操作') }
        account.status = 'ACTIVE'; account.version = 2; account.existingBinding = true
      }
      return ok({ account, temporaryPassword })
    }
    if (path.includes('notifications')) return ok({ items: [], total: 0, page: 0, size: 20 })
    if (path.includes('profile-review')) return ok([])
    return fail(403, 'Outside isolated fixture')
  })
  return state
}
async function open(page: Page) {
  await page.goto('/members'); await page.getByRole('button', { name: '企业账号', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
}
async function confirm(page: Page) {
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('textbox', { name: /核验依据/ }).fill('已通过留存渠道核验企业授权')
  await dialog.getByRole('checkbox').check()
}
test('administrator opens a prebound account and temporary password never enters browser storage', async ({ page }, info) => {
  const state = await fixture(page); await open(page)
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('button', { name: '开通企业账号', exact: true })).toBeDisabled()
  await dialog.getByRole('textbox', { name: '企业登录账号', exact: true }).fill('company.owner'); await confirm(page)
  await dialog.getByRole('button', { name: '开通企业账号', exact: true }).click()
  await expect(dialog.getByText('已绑定企业负责人', { exact: true })).toBeVisible()
  await expect(dialog.getByLabel('临时密码', { exact: true })).toHaveAttribute('type', 'password')
  await dialog.getByRole('button', { name: '显示密码', exact: true }).click()
  await expect(dialog.getByLabel('临时密码', { exact: true })).toHaveValue(temporaryPassword)
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain(temporaryPassword)
  expect(state.writes).toHaveLength(1); expect(state.errors).toEqual([])
  await dialog.getByRole('button', { name: '隐藏密码', exact: true }).click()
  await page.screenshot({ path: info.outputPath('enterprise-account-desktop.png') })
  await dialog.getByRole('button', { name: '清除临时密码显示' }).click(); await dialog.getByRole('button', { name: '关闭企业账号管理' }).click()
  await page.getByRole('button', { name: '企业账号', exact: true }).click()
  await expect(page.getByLabel('临时密码', { exact: true })).toHaveCount(0)
})
test('reset needs independent confirmation and a stale version never displays a new password', async ({ page }) => {
  const state = await fixture(page); state.account.status = 'ACTIVE'; state.account.version = 2; state.account.existingBinding = true; state.conflict = true
  page.on('dialog', dialog => dialog.accept()); await open(page); await confirm(page)
  await page.getByRole('button', { name: '重置临时密码', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('账号版本已更新')
  await expect(page.getByLabel('临时密码', { exact: true })).toHaveCount(0)
  state.conflict = false
  await page.getByRole('button', { name: '重置临时密码', exact: true }).click()
  await expect(page.getByLabel('临时密码', { exact: true })).toHaveValue(temporaryPassword)
})
test('interrupted provisioning refreshes durable state and only explicit resume retries the operation', async ({ page }) => {
  const state = await fixture(page); state.interrupted = true; await open(page); await confirm(page)
  await page.getByRole('button', { name: '开通企业账号', exact: true }).click()
  await expect(page.getByText('开户尚未完成', { exact: true })).toBeVisible(); expect(state.writes).toHaveLength(1)
  await expect(page.getByLabel('临时密码', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '恢复未完成操作', exact: true }).click()
  await expect(page.getByText('已绑定企业负责人', { exact: true })).toBeVisible()
  expect(state.writes[1].path).toContain('/resume'); expect(state.writes).toHaveLength(2)
})
test('ordinary member has no account-administration entry', async ({ page }) => {
  const state = await fixture(page, 'ENTERPRISE_MEMBER'); await page.goto('/members')
  await expect(page.getByRole('heading', { name: '会员企业', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '企业账号', exact: true })).toHaveCount(0); expect(state.writes).toHaveLength(0)
})
test('avatar change password uses reauthentication and an UPDATE_PASSWORD authorization action', async ({ page }) => {
  await fixture(page)
  await page.route('**/.well-known/openid-configuration', route => route.fulfill({ json: { issuer: authority, authorization_endpoint: `${authority}/protocol/openid-connect/auth`, token_endpoint: `${authority}/protocol/openid-connect/token`, jwks_uri: `${authority}/protocol/openid-connect/certs` } }))
  await page.route('**/protocol/openid-connect/auth?**', route => route.fulfill({ contentType: 'text/html', body: '<h1>Isolated authentication fixture</h1>' }))
  await page.goto('/members'); await page.getByRole('button', { name: '当前账号菜单' }).click()
  await page.getByRole('button', { name: '修改密码', exact: true }).click()
  await page.waitForURL(url => url.searchParams.get('kc_action') === 'UPDATE_PASSWORD')
  const query = new URL(page.url()).searchParams
  expect(query.get('prompt')).toBe('login'); expect(query.get('max_age')).toBe('0')
  expect(query.get('code_challenge_method')).toBe('S256'); expect(query.get('state')).toBeTruthy()
})
test('mobile disabled connector explains missing setup and prevents account writes', async ({ page }, info) => {
  await page.setViewportSize({ width: 390, height: 844 }); const state = await fixture(page); state.account.enabled = false
  await open(page); await expect(page.getByRole('dialog')).toContainText('开户服务尚未启用')
  await expect(page.getByRole('button', { name: '开通企业账号', exact: true })).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: info.outputPath('enterprise-account-mobile.png') })
})
