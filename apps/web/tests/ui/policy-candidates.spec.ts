import { expect, test, type Page } from '@playwright/test'

const policyId = '71000000-0000-4000-8000-000000000001'
const associationId = '71000000-0000-4000-8000-000000000002'
const enterpriseId = '71000000-0000-4000-8000-000000000003'
const authority = 'http://127.0.0.1:18188/identity/realms/entry-tests'
const policy = { id: policyId, title: '测试·供水监测政策', summary: '测试摘要：供水与监测。', status: 'PUBLISHED', version: 1,
  deleted: false, disabled: false, tags: ['供水', '监测'], level: '全国', category: '政策', publishDate: '2026-09-01', effectiveDate: null, authority: '测试机构', associationId, visibility: 'MEMBERS' }
async function fixture(page: Page, role = 'SYSTEM_ADMIN') {
  const state = { fail: false, empty: false, holdNext: false, release: () => {}, overviewFail: false, overviewRequests: [] as string[], name: '测试·传感设备企业', requests: [] as string[], methods: [] as string[], errors: [] as string[] }
  page.on('pageerror', e => state.errors.push(e.message))
  await page.addInitScript(({ authority, associationId }) => {
    sessionStorage.setItem('guanxian.system.context', JSON.stringify({ associationId, enterpriseId: null }))
    sessionStorage.setItem(`oidc.user:${authority}:entry-tests`, JSON.stringify({ access_token: 'fixture-token', token_type: 'Bearer', scope: 'openid', expires_at: Math.floor(Date.now()/1000)+3600, profile: { sub: 'fixture-user' } }))
  }, { authority, associationId })
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    const ok = (data: unknown) => route.fulfill({ json: { code: 'OK', data } })
    if (path.endsWith('/users/me')) return ok({ subject: 'fixture-user', username: 'fixture.user', displayName: '测试管理员', roles: [role], permissions: [], associationId, enterpriseId: role.startsWith('ENTERPRISE_') ? enterpriseId : null, organization: '虚构协会' })
    if (path.endsWith('/system-context/associations')) return ok([{ id: associationId, name: '虚构协会' }])
    if (path.includes('/system-context/')) return ok([])
    if (path === '/api/v1/policy-enterprise-candidates') {
      state.methods.push(route.request().method()); state.overviewRequests.push(url.searchParams.get('q') || '')
      if (state.overviewFail) return route.fulfill({ status: 403, json: { code: 'FORBIDDEN' } })
      return ok({ items: [{ policyId, policyTitle: policy.title, associationId, candidateCount: 1, examples: [{ enterpriseId, enterpriseName: state.name, evidence: [{ topic: '监测与预警' }] }] }], visiblePolicyCount: 1, page: 0, size: 10, examinedEnterpriseCount: 2, truncated: false, generatedAt: '2026-09-07T12:00:00Z' })
    }
    if (path.endsWith('/enterprise-candidates')) {
      state.methods.push(route.request().method()); state.requests.push(url.searchParams.get('q') || '')
      const name = state.name
      if (state.holdNext) { state.holdNext = false; await new Promise<void>(resolve => { state.release = resolve }) }
      if (state.fail) return route.fulfill({ status: 403, json: { code: 'FORBIDDEN', message: '当前范围不可访问' } })
      return ok({ policyId, policyVersion: 1, policyTitle: policy.title, method: 'PROFILE_TOPIC_CANDIDATE_V1',
        associationId, associationName: '虚构协会', ownEnterpriseOnly: false, query: url.searchParams.get('q') || '', eligibleEnterpriseCount: 2, examinedCount: 2,
        truncated: false, total: state.empty ? 0 : 1, page: 0, size: 20, generatedAt: '2026-09-07T12:00:00Z',
        policyMetadata: { region: '北京市', audience: '供水运营单位', sourceCheckedOn: '2026-09-05' },
        limitations: ['未核验政策正文条款；不构成政策适用、合规或申报资格结论'],
        items: state.empty ? [] : [{ enterpriseId, enterpriseName: name, category: null, enterpriseVersion: 2, relevance: 'MULTIPLE_CLUES', matchedTopics: 2,
          evidence: [{ topic: '监测与预警', policyField: '政策摘要', policyTerm: '监测', enterpriseField: '企业简介', enterpriseTerm: '传感器' }],
          missingEvidence: ['尚未核验主体身份、具体项目和资质条件'], assessment: { kind: 'COMPLIANCE_CLUE', applicability: 'UNVERIFIED', checks: [{ dimension: '适用地区', state: 'NEEDS_PROJECT_LOCATION', explanation: '仍须核对具体项目所在地' }] } }] })
    }
    if (path === '/api/v1/policies/page') return ok({ items: [policy], total: 1, page: 0, size: 20 })
    if (path === `/api/v1/policies/${policyId}`) return ok(policy)
    if (path.endsWith('/policies/levels')) return ok(['全国'])
    if (path.endsWith('/page') || path.includes('notifications')) return ok({ items: [], total: 0, page: 0, size: 20 })
    return ok([])
  })
  return state
}

test('policy detail exposes traceable candidates without claiming impact or making writes', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/policies')
  await expect(page.getByText('暂无正式影响分析，不代表政策与企业无关。', { exact: false })).toBeVisible()
  expect(state.requests).toEqual([]) // No N+1 candidate calls from list cards.
  await page.getByRole('button', { name: '查看详情 →', exact: true }).click()
  const panel = page.getByRole('region', { name: '政策与企业候选关联' })
  await expect(panel.getByRole('heading', { name: '测试·传感设备企业' })).toBeVisible()
  await expect(panel).toContainText('已对比 2 / 2 家，发现 1 家候选')
  await expect(panel).toContainText('不是适用概率')
  await expect(panel).toContainText('政策摘要出现“监测”；企业简介出现“传感器”')
  await expect(panel).toContainText('尚未核验主体身份')
  await expect(panel.getByRole('link', { name: '核对企业资料 →' })).toHaveAttribute('href', `/members?memberId=${enterpriseId}`)
  await panel.getByText('证据边界与待核实事项', { exact: true }).click()
  await expect(panel).toContainText('不构成政策适用')
  await panel.getByRole('textbox').fill('测试 & 设备')
  await panel.getByRole('button', { name: '查询候选' }).click()
  await expect.poll(() => state.requests.at(-1)).toBe('测试 & 设备')
  expect(state.methods.every(method => method === 'GET')).toBe(true)
  expect(state.errors).toEqual([])
})

test('closing a pending detail prevents its late response from overwriting reopened results', async ({ page }) => {
  const state = await fixture(page)
  state.holdNext = true; state.name = '测试·旧请求企业'
  await page.goto('/policies')
  await page.getByRole('button', { name: '查看详情 →', exact: true }).click()
  await expect.poll(() => state.requests.length).toBe(1)
  await page.locator('.modal-card').getByRole('button', { name: '×', exact: true }).click()
  state.name = '测试·最新资料企业'
  await page.getByRole('button', { name: '查看详情 →', exact: true }).click()
  const panel = page.getByRole('region', { name: '政策与企业候选关联' })
  await expect(panel.getByRole('heading', { name: '测试·最新资料企业' })).toBeVisible()
  const oldResponse = page.waitForResponse(response => response.url().includes('/enterprise-candidates'))
  state.release()
  await oldResponse
  await expect(panel.getByRole('heading', { name: '测试·最新资料企业' })).toBeVisible()
  await expect(panel.getByText('测试·旧请求企业')).toHaveCount(0)
  expect(state.errors).toEqual([])
})

test('candidate empty and permission failure clear old companies and support retry on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const state = await fixture(page)
  await page.goto('/policies')
  await page.getByRole('button', { name: '查看详情 →', exact: true }).click()
  const panel = page.getByRole('region', { name: '政策与企业候选关联' })
  await expect(panel.getByRole('heading', { name: '测试·传感设备企业' })).toBeVisible()
  state.empty = true
  await panel.getByRole('button', { name: '查询候选' }).click()
  await expect(panel).toContainText('这不是“不适用”结论')
  await expect(panel.getByRole('heading', { name: '测试·传感设备企业' })).toHaveCount(0)
  state.empty = false; state.fail = true
  await panel.getByRole('button', { name: '查询候选' }).click()
  await expect(panel.getByRole('button', { name: '重新加载' })).toBeVisible()
  await expect(panel.getByRole('link', { name: '核对企业资料 →' })).toHaveCount(0)
  state.fail = false
  await panel.getByRole('button', { name: '重新加载' }).click()
  await expect(panel.getByRole('heading', { name: '测试·传感设备企业' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  expect(state.errors).toEqual([])
})

test('overview automatically discovers companies with one batch read and opens evidence details', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/policies')
  const overview = page.getByRole('region', { name: '自动企业关联总览' })
  await expect(overview).toContainText('1 家候选')
  await expect(overview.getByRole('link', { name: state.name })).toHaveAttribute('href', `/members?memberId=${enterpriseId}`)
  expect(state.overviewRequests).toEqual([''])
  expect(state.requests).toEqual([])
  await overview.getByRole('button', { name: '核对依据与全部候选 →' }).click()
  const details = page.getByRole('region', { name: '政策与企业候选关联' })
  await expect(details).toContainText('合规义务线索 · 适用性未核实')
  await expect(details).toContainText('仍须核对具体项目所在地')
  expect(state.methods.every(method => method === 'GET')).toBe(true)
  expect(state.errors).toEqual([])
})

test('overview search and authorization errors never leave stale company links', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/policies')
  const overview = page.getByRole('region', { name: '自动企业关联总览' })
  await expect(overview.getByRole('link')).toHaveCount(1)
  await overview.getByRole('textbox').fill('供水 & 监测')
  await overview.getByRole('button', { name: '查询', exact: true }).click()
  await expect.poll(() => state.overviewRequests.at(-1)).toBe('供水 & 监测')
  state.overviewFail = true
  await overview.getByRole('button', { name: '刷新关联' }).click()
  await expect(overview.getByRole('button', { name: '重新加载' })).toBeVisible()
  await expect(overview.getByRole('link')).toHaveCount(0)
  expect(state.errors).toEqual([])
})

test('summary evidence is displayed as a reference and approval is disabled', async ({ page }) => {
  const state = await fixture(page)
  const analysis = { id: 'summary-test', policyDocumentId: policyId, policyTitle: policy.title, enterpriseId, enterpriseName: state.name,
    associationId, impactLevel: 'MEDIUM', summary: '摘要参考分析，不是法定适用结论', evidenceChunkIds: [], status: 'PENDING_REVIEW', version: 0,
    analysisMethod: 'DETERMINISTIC_TOPIC_V2', updatedAt: '2026-09-07T12:00:00Z', evidenceDetails: { basis: 'SUMMARY_REFERENCE', policyVersion: 1, enterpriseVersion: 2, capturedAt: '2026-09-07T12:00:00Z',
      references: [{ kind: 'POLICY_SUMMARY', id: policyId, title: policy.title, sourceUrl: 'javascript:alert(1)', quote: '供水企业应当监测' }],
      assessment: { kind: 'COMPLIANCE_CLUE', applicability: 'UNVERIFIED', checks: [{ dimension: '效力与时间', state: 'DATE_UNKNOWN', explanation: '缺少施行日期' }] } } }
  await page.route('**/api/v1/policy-impact-analyses/**', route => {
    const path = new URL(route.request().url()).pathname
    return route.fulfill({ json: { code: 'OK', data: path.endsWith('/page') ? { items: [analysis], total: 1, page: 0, size: 20 } : path.endsWith('/history') ? [] : analysis } })
  })
  await page.goto('/policies')
  await page.getByRole('button', { name: '查看影响分析', exact: false }).click()
  await page.locator('.impact-list').getByRole('button', { name: '查看详情 →' }).click()
  const evidence = page.getByRole('region', { name: '分析来源证据' })
  await expect(evidence).toContainText('摘要参考证据（非原文）')
  await expect(page.getByRole('button', { name: '审核通过', exact: true })).toBeDisabled()
  await evidence.locator('summary').click()
  await expect(evidence).toContainText('供水企业应当监测')
  await expect(evidence.getByRole('link')).toHaveCount(0)
  expect(state.errors).toEqual([])
})

test('candidate prepares the exact enterprise but writes only after explicit confirmation', async ({ page }) => {
  const state = await fixture(page)
  const writes: unknown[] = []
  const analysis = { id: 'prepared-summary', policyDocumentId: policyId, policyTitle: policy.title, enterpriseId,
    enterpriseName: state.name, associationId, impactLevel: 'MEDIUM', summary: '摘要参考分析', evidenceChunkIds: [],
    status: 'PENDING_REVIEW', version: 0, analysisMethod: 'DETERMINISTIC_TOPIC_V2',
    evidenceDetails: { basis: 'SUMMARY_REFERENCE', policyVersion: 1, enterpriseVersion: 2, capturedAt: '2026-09-08T01:00:00Z',
      references: [], assessment: { kind: 'RELATED_TOPIC', applicability: 'UNVERIFIED', checks: [] } } }
  await page.route('**/api/v1/policy-impact-analyses*', async route => {
    if (route.request().method() !== 'POST') return route.fallback()
    writes.push(route.request().postDataJSON())
    return route.fulfill({ status: 201, json: { code: 'OK', data: analysis } })
  })
  await page.route('**/api/v1/policy-impact-analyses/prepared-summary**', route => route.fulfill({ json: {
    code: 'OK', data: route.request().url().endsWith('/history') ? [] : analysis,
  } }))
  await page.goto('/policies')
  await page.getByRole('button', { name: '查看详情 →', exact: true }).click()
  await page.getByRole('button', { name: '以此企业准备分析', exact: true }).click()
  await expect(page.getByText(`已选择：${state.name}`, { exact: true })).toBeVisible()
  await expect(page.getByRole('textbox', { name: '搜索企业名称' })).toHaveCount(0)
  expect(writes).toEqual([])
  await page.screenshot({ path: test.info().outputPath('candidate-analysis-confirmation.png') })
  await page.getByRole('button', { name: '确认生成参考分析', exact: true }).click()
  await expect(page.getByRole('region', { name: '分析来源证据' })).toContainText('摘要参考证据（非原文）')
  expect(writes).toEqual([{ policyDocumentId: policyId, enterpriseId }])
  await expect(page.getByRole('button', { name: '审核通过', exact: true })).toBeDisabled()
  expect(state.errors).toEqual([])
})

test('enterprise identity cannot create a policy impact from a candidate', async ({ page }) => {
  const state = await fixture(page, 'ENTERPRISE_ADMIN')
  await page.goto('/policies')
  await page.getByRole('button', { name: '查看详情 →', exact: true }).click()
  await expect(page.getByRole('region', { name: '政策与企业候选关联' })).toContainText(state.name)
  await expect(page.getByRole('button', { name: '以此企业准备分析' })).toHaveCount(0)
  expect(state.methods.every(method => method === 'GET')).toBe(true)
  expect(state.errors).toEqual([])
})

test('closing the confirmation prevents a late create response from reopening a result', async ({ page }) => {
  const state = await fixture(page)
  let release: () => void = () => {}
  let writes = 0
  await page.route('**/api/v1/policy-impact-analyses', async route => {
    writes++
    await new Promise<void>(resolve => { release = resolve })
    await route.fulfill({ status: 201, json: { code: 'OK', data: { id: 'late-result' } } })
  })
  await page.goto('/policies')
  await page.getByRole('button', { name: '查看详情 →', exact: true }).click()
  await page.getByRole('button', { name: '以此企业准备分析', exact: true }).click()
  await page.getByRole('button', { name: '确认生成参考分析', exact: true }).click()
  await expect.poll(() => writes).toBe(1)
  await page.getByRole('button', { name: '取消', exact: true }).click()
  const response = page.waitForResponse(value => value.url().endsWith('/policy-impact-analyses'))
  release()
  await response
  await expect(page.locator('.modal-backdrop')).toHaveCount(0)
  expect(writes).toBe(1)
  expect(state.errors).toEqual([])
})
