import { randomUUID } from 'node:crypto'
import { expect, test, type Page } from '@playwright/test'
import { authenticatedPage, e2eUsers, waitForApiWrite } from './support'

// Real OIDC, HTTP services and PostgreSQL. Never run writes against a production origin.
test('真实政策闭环：摘要发现企业、确认后生成参考分析、后端拒绝直接批准', async ({ browser, baseURL }) => {
  expect(new URL(baseURL!).origin).toBe('http://127.0.0.1:18082')
  const admin = await authenticatedPage(browser, e2eUsers.associationAdmin)
  const enterprise = await authenticatedPage(browser, e2eUsers.supplierAdmin)
  const title = `E2E政策摘要候选-${randomUUID()}`
  const summary = '隔离测试资料：管线监测、泄漏预警与传感器技术的业务线索，不是真实政策。'
  try {
    const page = admin.page
    await page.goto('/policies')
    await page.getByRole('button', { name: '+ 收录政策', exact: true }).click()
    const form = page.locator('form.modal-card')
    await form.getByLabel('标题 *', { exact: true }).fill(title)
    await form.getByLabel('摘要', { exact: true }).fill(summary)
    await form.getByLabel('可见范围').selectOption('MEMBERS')
    const createdPromise = page.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/policies'
      && r.request().method() === 'POST')
    await form.getByRole('button', { name: '保存草稿', exact: true }).click()
    const created = await createdPromise
    expect(created.status()).toBe(201)
    const policy = (await created.json()).data
    await page.goto(`/policies?policyId=${policy.id}`)
    await waitForApiWrite(page, /\/policies\/[0-9a-f-]+\/submit$/, async () => {
      await page.getByRole('button', { name: '提交审核', exact: true }).click()
    })
    await waitForApiWrite(page, /\/policies\/[0-9a-f-]+\/review$/, async () => {
      await page.getByRole('button', { name: '审核发布', exact: true }).click()
    })

    const impactWrites: string[] = []
    page.on('request', request => {
      if (new URL(request.url()).pathname === '/api/v1/policy-impact-analyses'
        && request.method() === 'POST') impactWrites.push(request.postData() || '')
    })
    const candidateResponse = page.waitForResponse(r => new URL(r.url()).pathname
      === `/api/v1/policies/${policy.id}/enterprise-candidates`)
    await page.goto(`/policies?policyId=${policy.id}`)
    const candidates = await candidateResponse
    expect(candidates.status()).toBe(200)
    const result = (await candidates.json()).data
    expect(result.total).toBeGreaterThan(0)
    expect(result.associationId).toBe('00000000-0000-0000-0000-000000000106')
    expect(result.items.every((item: { assessment: { applicability: string } }) =>
      item.assessment.applicability === 'UNVERIFIED')).toBe(true)
    const panel = page.getByRole('region', { name: '政策与企业候选关联' })
    const candidate = panel.locator('.candidate-card').first()
    const candidateName = await candidate.getByRole('heading', { level: 4 }).innerText()
    await candidate.getByRole('button', { name: '以此企业准备分析' }).click()
    await expect(page.getByText(`已选择：${candidateName}`, { exact: true })).toBeVisible()
    expect(impactWrites).toEqual([])
    const analysisPromise = page.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/policy-impact-analyses'
      && r.request().method() === 'POST')
    await page.getByRole('button', { name: '确认生成参考分析', exact: true }).click()
    const analysisResponse = await analysisPromise
    expect(analysisResponse.status()).toBe(201)
    const analysis = (await analysisResponse.json()).data
    expect(impactWrites).toHaveLength(1)
    expect(analysis.enterpriseName).toBe(candidateName)
    expect(analysis.evidenceDetails.basis).toBe('SUMMARY_REFERENCE')
    expect(analysis.evidenceDetails.references[0].quote).toBe(summary)
    expect(analysis.evidenceChunkIds).toEqual([])
    expect(analysis.impactLevel).not.toBe('HIGH')
    await expect(page.getByRole('region', { name: '分析来源证据' })).toContainText('摘要参考证据（非原文）')
    await expect(page.getByRole('button', { name: '审核通过', exact: true })).toBeDisabled()
    const refused = await sessionApi(page, `/policy-impact-analyses/${analysis.id}/review`, 'PUT',
      { approved: true, comment: '隔离测试：尝试绕过前端，必须被拒绝' }, analysis.version)
    expect(refused.status).toBe(412)
    const persisted = await sessionApi(page, `/policy-impact-analyses/${analysis.id}`)
    expect(persisted.data.status).toBe('PENDING_REVIEW')
    expect(persisted.data.version).toBe(analysis.version)

    await enterprise.page.goto(`/policies?policyId=${policy.id}`)
    const own = await sessionApi(enterprise.page, `/policies/${policy.id}/enterprise-candidates`)
    expect(own.status).toBe(200)
    expect(own.data.ownEnterpriseOnly).toBe(true)
    expect(own.data.items.length).toBeGreaterThan(0)
    expect(own.data.items.every((item: { enterpriseId: string }) =>
      item.enterpriseId === '00000000-0000-0000-0000-000000000202')).toBe(true)
    await expect(enterprise.page.getByRole('button', { name: '以此企业准备分析' })).toHaveCount(0)
    const denied = await sessionApi(enterprise.page, '/policy-impact-analyses', 'POST', {
      policyDocumentId: policy.id, enterpriseId: '00000000-0000-0000-0000-000000000202',
    })
    expect(denied.status).toBe(403)
  } finally {
    await Promise.all([admin.context.close(), enterprise.context.close()])
  }
})

async function sessionApi(page: Page, path: string, method = 'GET', body?: unknown, version?: number) {
  return page.evaluate(async ({ path, method, body, version }) => {
    if (location.origin !== 'http://127.0.0.1:18082') throw new Error('Only disposable E2E origin is allowed')
    const key = Object.keys(sessionStorage).find(key => key.startsWith('oidc.user:'))
    if (!key) throw new Error('Missing real OIDC session')
    const token = JSON.parse(sessionStorage.getItem(key)!).access_token
    const headers: Record<string, string> = { Authorization: `Bearer ${token}` }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (version !== undefined) headers['If-Match'] = `"${version}"`
    const response = await fetch('/api/v1' + path, { method, headers, cache: 'no-store',
      body: body === undefined ? undefined : JSON.stringify(body) })
    const payload = await response.json()
    return { status: response.status, data: payload.data, code: payload.code }
  }, { path, method, body, version })
}
