import { expect, test, type Page } from '@playwright/test'

const associationId = '61000000-0000-4000-8000-000000000001'
const authority = 'http://127.0.0.1:18188/identity/realms/entry-tests'
async function fixture(page: Page) {
  const state = { denied: false, queries: [] as string[], errors: [] as string[] }
  page.on('pageerror', e => state.errors.push(e.message))
  await page.addInitScript(({ authority, associationId }) => {
    sessionStorage.setItem('guanxian.system.context', JSON.stringify({ associationId, enterpriseId: null }))
    sessionStorage.setItem(`oidc.user:${authority}:entry-tests`, JSON.stringify({ access_token: 'fixture-token', token_type: 'Bearer', scope: 'openid', expires_at: Math.floor(Date.now()/1000)+3600, profile: { sub: 'fixture-user' } }))
  }, { authority, associationId })
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    const ok = (data: unknown) => route.fulfill({ json: { code: 'OK', data } })
    if (path.endsWith('/users/me')) return ok({ subject: 'fixture-user', username: 'fixture.user', displayName: '测试管理员', roles: ['SYSTEM_ADMIN'], permissions: [], associationId, enterpriseId: null, organization: '虚构协会' })
    if (path.endsWith('/system-context/associations')) return ok([{ id: associationId, name: '虚构协会' }])
    if (path.includes('/system-context/')) return ok([])
    if (path.endsWith('/source-directory')) {
      state.queries.push(url.searchParams.get('q') || '')
      if (state.denied) return route.fulfill({ status: 403, json: { code: 'SCOPE_FORBIDDEN', message: '当前范围不可访问' } })
      const kind = url.searchParams.get('kind')
      return ok({ items: [{ id: 'fixture-id', sourceId: kind === 'TENDER' ? 'BID-TEST' : 'ORG-TEST', title: kind === 'TENDER' ? '测试·地下管线设备采购公告' : '测试·企业供给资料',
        enterpriseId: kind === 'ENTERPRISE' ? '62000000-0000-4000-8000-000000000001' : null,
        fields: kind === 'TENDER' ? { '采购内容': '仅用于界面测试的公告摘要，不是真实招标。', '截止或开标时间': '2020-01-01 09:00:00', '原文链接': 'https://example.com/notice' } : { '主要产品与服务': '仅用于测试的服务说明', '网址': 'www.example.com' }, importedAt: '2026-09-07T12:00:00Z' }], total: 1, page: 0, size: 20 })
    }
    if (path.includes('notifications')) return ok({ items: [], total: 0, page: 0, size: 20 })
    if (path.includes('/page')) return ok({ items: [], total: 0, page: 0, size: 20 })
    return ok([])
  })
  return state
}

test('source catalog preserves missing fields and links to the live member profile', async ({ page }, info) => {
  const state = await fixture(page)
  await page.goto('/ecosystem')
  await page.getByRole('button', { name: '企业供给资料', exact: true }).click()
  const directory = page.getByRole('region', { name: '企业供给资料', exact: true })
  await expect(directory.getByRole('heading', { name: '测试·企业供给资料' })).toBeVisible()
  await expect(directory.getByText('待补充').first()).toBeVisible()
  await expect(directory.getByRole('link', { name: '企业详情' })).toHaveAttribute('href', /\/members\?memberId=/)
  await expect(directory.getByRole('link', { name: /查看来源/ })).toHaveCount(0)
  await directory.getByRole('textbox').fill('设备')
  await directory.getByRole('button', { name: '搜索', exact: true }).click()
  await expect.poll(() => state.queries.at(-1)).toBe('设备')
  await expect(directory.getByRole('heading', { name: '测试·企业供给资料' })).toBeVisible()
  await page.screenshot({ path: info.outputPath('source-directory-desktop.png'), fullPage: true })
  state.denied = true
  await directory.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(directory.getByRole('heading', { name: '测试·企业供给资料' })).toHaveCount(0)
  expect(state.errors).toEqual([])
})

test('external notices are visibly distinct from member demands and fit mobile', async ({ page }, info) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const state = await fixture(page)
  await page.goto('/ecosystem')
  await page.getByRole('button', { name: '外部招投标', exact: true }).click()
  const directory = page.getByRole('region', { name: '外部招投标', exact: true })
  await expect(directory).toContainText('不是会员发布的合作需求')
  await expect(directory).toContainText('已过所载截止时间')
  await expect(directory.getByRole('link', { name: /查看来源/ })).toHaveAttribute('rel', 'noopener noreferrer')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  expect(state.errors).toEqual([])
  await page.screenshot({ path: info.outputPath('source-directory-mobile.png'), fullPage: true })
})
