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

test('annual evidence separates activity, candidate history and original snapshots on mobile', async ({ page }, info) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const state = await fixture(page)
  await page.route('**/api/v1/source-directory*', route => {
    const activity = new URL(route.request().url()).searchParams.get('kind') === 'ACTIVITY'
    return route.fulfill({ json: { code: 'OK', data: { items: [{ id: 'annual-fixture', sourceId: 'BID-049', title: '原始项目标题',
      fields: { 记录状态: activity ? '公告计划' : '候选公示', 记录状态代码: activity ? 'PLANNED' : 'CANDIDATE_NOTICE',
        证据摘要: '仅用于界面测试', 关联企业及角色: '虚构甲企业：采购人；虚构乙企业：候选供应商，不等于中标',
        金额及口径: '100000.00 元；投标报价（非中标金额）', 原文链接: 'https://example.test/notice',
        后续结果: '最终中标结果未核实', 核验日期: '2026-09-08' },
      originalFields: { 采购内容: '原始摘要仍保留' },
      evidence: { recordId: 'EV-TEST', title: activity ? '公开活动计划测试' : '历史候选公示测试', checkedOn: '2026-09-08',
        sourceUrl: 'https://example.test/notice', supportingUrls: ['javascript:alert(1)', 'https://example.test/other'] },
      importedAt: '2026-09-08T00:00:00Z',
    }], total: 1, page: 0, size: 20 } } })
  })
  await page.goto('/ecosystem')
  await page.getByRole('button', { name: '外部招投标', exact: true }).click()
  let directory = page.getByRole('region', { name: '外部招投标', exact: true })
  await expect(directory).toContainText('候选公示 · 非在招机会')
  await expect(directory).toContainText('非中标金额')
  await expect(directory).toContainText('状态核验截至 2026-09-08，不代表实时进展')
  await directory.getByText('查看保留的原始项目资料').click()
  await expect(directory).toContainText('原始摘要仍保留')
  await expect(directory.getByRole('link', { name: /补充证据/ })).toHaveCount(1)
  await page.getByRole('button', { name: '企业活动与公开动态', exact: true }).click()
  directory = page.getByRole('region', { name: '企业活动与公开动态', exact: true })
  await expect(directory).toContainText('不是平台确认的合作')
  await expect(directory.getByRole('heading', { name: '公开活动计划测试' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  expect(state.errors).toEqual([])
  await page.screenshot({ path: info.outputPath('annual-activity-mobile.png'), fullPage: true })
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

test('corrected notices retain original fields and render separate safe evidence links on mobile', async ({ page }, info) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const state = await fixture(page)
  await page.route('**/api/v1/source-directory*', route => route.fulfill({ json: { code: 'OK', data: {
    items: [{ id: 'corrected-test', sourceId: 'BID-CORRECTION-TEST', title: '更正展示测试公告', importedAt: '2026-09-07T12:00:00Z',
      fields: { 原文链接: 'https://example.test/original', 更正公告: 'https://example.test/correction', 采购内容: '测试摘要' },
      originalFields: { 原文链接: 'https://example.test/original；更正：https://example.test/correction', 采购内容: '原始测试摘要' },
      correction: { id: 'test-correction', checkedOn: '2026-09-08', reason: '仅拆分链接，资格条件未核验', evidenceUrls: ['javascript:alert(1)', 'https://example.test/evidence'] },
    }], total: 1, page: 0, size: 20,
  } } }))
  await page.goto('/ecosystem')
  await page.getByRole('button', { name: '外部招投标', exact: true }).click()
  const directory = page.getByRole('region', { name: '外部招投标', exact: true })
  await expect(directory.getByRole('link', { name: '查看来源原文 ↗' })).toHaveAttribute('href', 'https://example.test/original')
  await expect(directory.getByRole('link', { name: '查看更正公告 ↗' })).toHaveAttribute('href', 'https://example.test/correction')
  await directory.getByText('元数据更正记录', { exact: false }).click()
  await expect(directory).toContainText('资格条件未核验')
  await expect(directory).toContainText('原始测试摘要')
  await expect(directory.getByRole('link', { name: /更正依据/ })).toHaveCount(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  expect(state.errors).toEqual([])
  await page.screenshot({ path: info.outputPath('source-correction-mobile.png'), fullPage: true })
})

test('tender plans and acquisition deadlines stay distinct from bidding and expose review boundaries', async ({ page }, info) => {
  await page.clock.install({ time: new Date('2026-09-08T08:59:30Z') })
  await page.setViewportSize({ width: 390, height: 844 })
  const state = await fixture(page)
  await page.route('**/api/v1/source-directory*', route => route.fulfill({ json: { code: 'OK', data: {
    items: [
      { id: 'plan', sourceId: 'PLAN-TEST', title: '测试·雨水管线改造计划', importedAt: '2026-09-08T00:00:00Z',
        fields: { 公告类型: '招标计划', 预计公告日期: '2026-10-09', 采购内容: '测试雨水施工', 原文链接: 'https://example.test/plan' } },
      { id: 'notice', sourceId: 'NOTICE-TEST', title: '测试·供水设备公告', importedAt: '2026-09-08T00:00:00Z',
        fields: { 公告类型: '采购公告', 文件获取截止时间: '2026-09-08 17:00:00', 截止或开标时间: '2026-09-22 09:00:00',
          提交截止类型: '投标文件截止', 业务关联说明: '供水设备方向线索，未确认供应商资格', 核验边界: '更正及终止链未穷尽' } },
    ], total: 2, page: 0, size: 20,
  } } }))
  await page.goto('/ecosystem')
  await page.getByRole('button', { name: '外部招投标', exact: true }).click()
  const directory = page.getByRole('region', { name: '外部招投标', exact: true })
  await expect(directory).toContainText('招标计划 · 等待正式公告')
  await expect(directory).toContainText('尚未到所载截止时间')
  // Status refreshes without a page reload when the acquisition window closes.
  await page.clock.fastForward(61_000)
  await expect(directory).toContainText('已过文件获取期 · 请核对是否已获取文件')
  await expect(directory).toContainText('供水设备方向线索，未确认供应商资格')
  await expect(directory).toContainText('更正及终止链未穷尽')
  await expect(directory.getByRole('button', { name: /投标|确认匹配|报名/ })).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  expect(state.errors).toEqual([])
  await page.screenshot({ path: info.outputPath('tender-leads-mobile.png'), fullPage: true })
})
