import { expect, test, type Page } from '@playwright/test'
import { authenticatedPage, e2eUsers } from './support'
import type { ProfileWorkflow, PublicEnterprise } from '../../src/services/profile-workflow'

// Only the disposable local/CI topology. Never run this write journey on a real site.
test('真实 Keycloak 与 PostgreSQL：审核、授权和发布分离，游客不能读取草稿与内部字段', async ({ browser, request, baseURL }) => {
  expect(new URL(baseURL!).origin).toBe('http://127.0.0.1:18082')
  const owner = await authenticatedPage(browser, e2eUsers.enterpriseAdmin)
  const reviewer = await authenticatedPage(browser, e2eUsers.associationAdmin)
  const foreign = await authenticatedPage(browser, e2eUsers.supplierAdmin)
  const guest = await browser.newContext()
  try {
    const mine = await api<{ profile: { id: string } }>(owner.page, '/my-enterprise')
    expect(mine.status).toBe(200)
    const id = mine.data.profile.id
    const path = `/enterprise-profiles/${id}`
    const publicPath = `/api/v1/public/enterprises/${id}`
    expect((await request.get(publicPath)).status()).toBe(404)
    expect((await request.get(path.replace('/enterprise-profiles', '/api/v1/enterprise-profiles'))).status()).toBe(401)
    expect((await api(foreign.page, path)).status).toBe(403)
    let state = (await api<ProfileWorkflow>(owner.page, path)).data
    const draft = { ...state.official, introduction: 'CI 公开资料：已核对的企业简介', contactPhone: 'CI-PRIVATE-PHONE', contactEmail: 'private@invalid.example' }
    state = (await write(owner.page, path + '/draft', state, { baseVersion: state.official.version, content: draft }, 'PUT')).data
    expect((await request.get(publicPath)).status()).toBe(404)
    state = (await write(owner.page, path + '/submit', state)).data
    expect((await api(owner.page, path + '/review', 'POST', { approve: true, note: '尝试自审' }, state.version)).status).toBe(403)
    state = (await write(reviewer.page, path + '/review', state, { approve: false, note: 'CI：补充资料说明' })).data
    expect((await api<ProfileWorkflow>(owner.page, path)).data.draft?.reviewNote).toBe('CI：补充资料说明')
    state = (await write(owner.page, path + '/draft', state, { baseVersion: state.official.version, content: draft }, 'PUT')).data
    state = (await write(owner.page, path + '/submit', state)).data
    state = (await write(reviewer.page, path + '/review', state, { approve: true, note: 'CI：独立核验公开白名单' })).data
    expect((await request.get(publicPath)).status()).toBe(404)
    expect((await api(reviewer.page, path + '/publish', 'POST', undefined, state.version)).status).toBe(409)
    state = (await write(owner.page, path + '/consent', state, { confirmed: true })).data
    expect((await request.get(publicPath)).status()).toBe(404)
    state = (await write(reviewer.page, path + '/publish', state)).data
    const published = await request.get(publicPath)
    expect(published.status()).toBe(200)
    expect(published.headers()['cache-control']).toBe('no-store')
    const snapshot = (await published.json()).data as PublicEnterprise
    expect(snapshot.introduction).toBe(draft.introduction)
    expect(Object.keys(snapshot).sort()).toEqual(['id', 'name', 'category', 'introduction', 'capabilities', 'products', 'services', 'applicationScenarios', 'publicationId', 'publishedAt'].sort())
    expect(JSON.stringify(snapshot)).not.toContain('CI-PRIVATE')
    const page = await guest.newPage()
    await page.goto('/public')
    await expect(page.getByRole('heading', { name: draft.name, exact: true })).toBeVisible()
    await expect(page.locator('body')).not.toContainText('CI-PRIVATE-PHONE')
    await expect(page.locator('.app-shell')).toHaveCount(0)
    state = (await write(owner.page, path + '/draft', state, { baseVersion: state.official.version, content: { ...draft, introduction: 'CI 未审核的新草稿' } }, 'PUT')).data
    expect((await (await request.get(publicPath)).json()).data.introduction).toBe(snapshot.introduction)
    state = (await write(owner.page, path + '/withdraw', state, { note: 'CI 验收结束，撤回展示' })).data
    expect(state.published).toBe(false)
    expect((await request.get(publicPath)).status()).toBe(404)
    await page.reload()
    await expect(page.getByRole('heading', { name: draft.name, exact: true })).toHaveCount(0)
    expect((await request.post('/api/v1/public/enterprises', { data: {} })).status()).toBe(401)
  } finally {
    await Promise.all([owner.context.close(), reviewer.context.close(), foreign.context.close(), guest.close()])
  }
})

async function api<T = unknown>(page: Page, path: string, method = 'GET', body?: unknown, version?: number): Promise<{status: number; data: T}> {
  return page.evaluate(async ({ path, method, body, version }) => {
    const key = Object.keys(sessionStorage).find(key => key.startsWith('oidc.user:'))
    if (!key) throw new Error('Missing real OIDC session')
    const token = JSON.parse(sessionStorage.getItem(key)!).access_token
    const response = await fetch('/api/v1' + path, {
      method, cache: 'no-store', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...(version === undefined ? {} : { 'If-Match': `"${version}"` }) },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const payload = await response.json()
    return { status: response.status, data: payload.data }
  }, { path, method, body, version })
}
async function write(page: Page, path: string, state: ProfileWorkflow, body?: unknown, method = 'POST') {
  const result = await api<ProfileWorkflow>(page, path, method, body, state.version)
  expect(result.status, `${method} ${path}`).toBe(200)
  return result
}
