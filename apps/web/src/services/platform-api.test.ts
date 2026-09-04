import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MemberProfile, MemberUpsertPayload } from '../types/domain'

const browserWindow = {
  setTimeout: globalThis.setTimeout.bind(globalThis),
  clearTimeout: globalThis.clearTimeout.bind(globalThis),
}

const profile: MemberProfile = {
  id: '20000000-0000-0000-0000-000000000001',
  associationId: '00000000-0000-0000-0000-000000000100',
  name: '测试会员企业',
  unifiedSocialCreditCode: '91110000TEST000001',
  category: '技术服务单位',
  address: '北京市',
  contactName: '张工',
  contactPhone: '13800000000',
  introduction: '测试简介',
  capabilities: ['管线监测'],
  products: ['监测平台'],
  cooperationNeeds: ['场景合作'],
  visibility: 'MEMBERS',
  status: 'ACTIVE',
  version: 7,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-08-20T00:00:00Z',
  deletedAt: null,
  deletedBySubject: null,
  statusBeforeDelete: null,
}

async function loadApi() {
  vi.resetModules()
  vi.stubEnv('MODE', 'test')
  vi.stubEnv('VITE_API_BASE_URL', '/api/v1')
  return import('./platform-api')
}

describe('member ETag API contract', () => {
  beforeEach(() => {
    vi.stubGlobal('window', browserWindow)
    vi.stubGlobal('crypto', { randomUUID: () => 'etag-test-request' })
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('captures the strong ETag returned by member GET', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      Response.json(
        { code: 'OK', data: profile },
        { headers: { ETag: '"7"', 'X-Request-Id': 'etag-get' } },
      ),
    ))
    const { platformApi } = await loadApi()

    await expect(platformApi.member(profile.id)).resolves.toEqual({
      member: profile,
      etag: '"7"',
    })
  })

  it('sends the exact ETag in If-Match and stores the updated ETag', async () => {
    const updated = { ...profile, name: '已更新会员企业', version: 8 }
    const fetchMock = vi.fn().mockResolvedValue(
      Response.json(
        { code: 'OK', data: updated },
        { headers: { ETag: '"8"', 'X-Request-Id': 'etag-put' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    const { platformApi } = await loadApi()
    const payload: MemberUpsertPayload = {
      name: updated.name,
      unifiedSocialCreditCode: updated.unifiedSocialCreditCode,
      category: updated.category,
      address: updated.address,
      contactName: updated.contactName,
      contactPhone: updated.contactPhone,
      introduction: updated.introduction,
      capabilities: updated.capabilities,
      products: updated.products,
      cooperationNeeds: updated.cooperationNeeds,
      visibility: updated.visibility,
      status: updated.status,
    }

    await expect(platformApi.updateMember(profile.id, payload, '"7"')).resolves.toEqual({
      member: updated,
      etag: '"8"',
    })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe(`/api/v1/members/${profile.id}`)
    expect(init.method).toBe('PUT')
    expect(new Headers(init.headers).get('If-Match')).toBe('"7"')
    expect(JSON.parse(String(init.body))).toMatchObject({ name: '已更新会员企业' })
  })

  it('fails closed when GET omits a valid strong ETag', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      Response.json({ code: 'OK', data: profile }, { headers: { 'X-Request-Id': 'etag-missing' } }),
    ))
    const { platformApi } = await loadApi()

    await expect(platformApi.member(profile.id)).rejects.toMatchObject({
      code: 'MISSING_ETAG',
      requestId: 'etag-missing',
    })
  })

  it.each(['W/"0"', 'W/"7"'])('does not promote weak response ETag %j to a write version', async (etag) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      Response.json({ code: 'OK', data: profile }, {
        headers: { ETag: etag, 'X-Request-Id': 'etag-weak-response' },
      }),
    ))
    const { platformApi } = await loadApi()

    await expect(platformApi.member(profile.id)).rejects.toMatchObject({
      code: 'MISSING_ETAG',
      requestId: 'etag-weak-response',
    })
  })

  it('preserves a stale-write 412 response for the edit page conflict flow', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      Response.json(
        { code: 'PRECONDITION_FAILED', message: 'member version does not match If-Match', data: null },
        { status: 412, headers: { 'X-Request-Id': 'etag-conflict' } },
      ),
    ))
    const { platformApi } = await loadApi()
    const payload = {
      name: profile.name,
      unifiedSocialCreditCode: profile.unifiedSocialCreditCode,
      category: profile.category,
      address: profile.address,
      contactName: profile.contactName,
      contactPhone: profile.contactPhone,
      introduction: profile.introduction,
      capabilities: profile.capabilities,
      products: profile.products,
      cooperationNeeds: profile.cooperationNeeds,
      visibility: profile.visibility,
      status: profile.status,
    }

    await expect(platformApi.updateMember(profile.id, payload, '"7"')).rejects.toMatchObject({
      status: 412,
      code: 'PRECONDITION_FAILED',
      requestId: 'etag-conflict',
    })
  })

  it('creates and reviews members with strong ETag propagation', async () => {
    const created = { ...profile, status: 'PENDING_REVIEW' as const, version: 0 }
    const reviewed = { ...created, status: 'ACTIVE' as const, version: 1 }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ code: 'OK', data: created }, { status: 201, headers: { ETag: '"0"' } }))
      .mockResolvedValueOnce(Response.json({ code: 'OK', data: reviewed }, { headers: { ETag: '"1"' } }))
    vi.stubGlobal('fetch', fetchMock)
    const { platformApi } = await loadApi()
    const payload: MemberUpsertPayload = {
      name: profile.name, unifiedSocialCreditCode: profile.unifiedSocialCreditCode, category: profile.category,
      address: profile.address, contactName: profile.contactName, contactPhone: profile.contactPhone,
      introduction: profile.introduction, capabilities: profile.capabilities, products: profile.products,
      cooperationNeeds: profile.cooperationNeeds, visibility: profile.visibility, status: 'PENDING_REVIEW',
    }

    await expect(platformApi.createMember(payload)).resolves.toEqual({ member: created, etag: '"0"' })
    await expect(platformApi.reviewMember(profile.id, 'ACTIVE', '  资料通过  ', '"0"')).resolves.toEqual({ member: reviewed, etag: '"1"' })

    const [reviewUrl, reviewInit] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(reviewUrl).toBe(`/api/v1/members/${profile.id}/review`)
    expect(new Headers(reviewInit.headers).get('If-Match')).toBe('"0"')
    expect(JSON.parse(String(reviewInit.body))).toEqual({ decision: 'ACTIVE', comment: '资料通过' })
  })

  it('uploads the survey as browser-owned multipart data and commits its batch', async () => {
    const preview = { batchId: 'batch-1', filename: 'survey.xlsx', status: 'PREVIEWED', totalRows: 1, validRows: 1, invalidRows: 0, createdAt: '2026-08-20T00:00:00Z', rows: [] }
    const result = { batchId: 'batch-1', importedRows: 1, invalidRows: 0, enterpriseIds: [profile.id] }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ code: 'OK', data: preview }))
      .mockResolvedValueOnce(Response.json({ code: 'OK', data: result }))
    vi.stubGlobal('fetch', fetchMock)
    const { platformApi } = await loadApi()
    const file = new File(['xlsx'], 'survey.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })

    await expect(platformApi.previewMemberImport(file)).resolves.toEqual(preview)
    await expect(platformApi.commitMemberImport('batch-1')).resolves.toEqual(result)
    const [, previewInit] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(previewInit.body).toBeInstanceOf(FormData)
    expect(new Headers(previewInit.headers).has('Content-Type')).toBe(false)
  })

  it('downloads the survey template as a binary blob', async () => {
    const bytes = new Uint8Array([80, 75, 3, 4])
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(bytes, { headers: { 'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' } })))
    const { platformApi } = await loadApi()
    const blob = await platformApi.downloadMemberTemplate()
    expect(blob).toBeInstanceOf(Blob)
    expect(new Uint8Array(await blob.arrayBuffer())).toEqual(bytes)
  })

  it('sends assistant questions through the authenticated knowledge endpoint', async () => {
    const answer = {
      answer: '应建立巡检制度。[1]',
      citations: [],
      traceId: 'trace-1',
      mode: 'RETRIEVAL_SUMMARY',
      retrievalMode: 'LEXICAL',
      inputTokens: 20,
      outputTokens: 8,
      estimatedCost: 0,
    }
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ code: 'OK', data: answer }))
    vi.stubGlobal('fetch', fetchMock)
    const { platformApi } = await loadApi()

    await expect(platformApi.askPolicyQuestion('  有哪些巡检要求？  ', 5, 'association-1')).resolves.toEqual(answer)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/knowledge/questions')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({
      question: '  有哪些巡检要求？  ',
      maxCitations: 5,
      associationId: 'association-1',
    })
  })

  it('sends stateful chat messages with page and association context', async () => {
    const answer = {
      answer: '可以从会员企业页面执行批量导入。',
      citations: [],
      traceId: 'trace-2',
      mode: 'SPRING_AI_AGENT',
      retrievalMode: 'LEXICAL',
      inputTokens: 30,
      outputTokens: 10,
      estimatedCost: 0,
      conversationId: '11111111-1111-4111-8111-111111111111',
      modelConnected: true,
    }
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ code: 'OK', data: answer }))
    vi.stubGlobal('fetch', fetchMock)
    const { platformApi } = await loadApi()

    await expect(platformApi.chatWithAssistant(
      '批量导入在哪里？',
      answer.conversationId,
      '会员企业',
      '/members',
      5,
      'association-1',
    )).resolves.toEqual(answer)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/assistant/chat')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({
      message: '批量导入在哪里？',
      conversationId: answer.conversationId,
      pageTitle: '会员企业',
      pagePath: '/members',
      maxCitations: 5,
      associationId: 'association-1',
    })
  })

  it('streams assistant deltas and resolves only with the completed answer', async () => {
    const answer = {
      answer: '当前有 5 家会员企业。',
      citations: [],
      traceId: 'trace-stream-1',
      mode: 'SPRING_AI_AGENT',
      retrievalMode: 'LEXICAL',
      inputTokens: 42,
      outputTokens: 11,
      estimatedCost: 0,
      conversationId: '11111111-1111-4111-8111-111111111111',
      modelConnected: true,
    }
    const payload = [
      { type: 'start', conversationId: answer.conversationId, delta: null, answer: null, error: null },
      { type: 'delta', conversationId: answer.conversationId, delta: '当前有 ', answer: null, error: null },
      { type: 'delta', conversationId: answer.conversationId, delta: '5 家会员企业。', answer: null, error: null },
      { type: 'complete', conversationId: answer.conversationId, delta: null, answer, error: null },
    ].map((event) => `data:${JSON.stringify(event)}\n\n`).join('')
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(payload))
        controller.close()
      },
    })
    const fetchMock = vi.fn().mockResolvedValue(new Response(stream, { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const { platformApi } = await loadApi()
    const deltas: string[] = []

    await expect(platformApi.streamAssistant(
      '有多少会员企业？',
      answer.conversationId,
      '会员企业',
      '/members',
      (delta) => { deltas.push(delta) },
      undefined,
      5,
      'association-1',
    )).resolves.toEqual(answer)

    expect(deltas).toEqual(['当前有 ', '5 家会员企业。'])
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/assistant/chat/stream')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toMatchObject({
      message: '有多少会员企业？',
      conversationId: answer.conversationId,
      pagePath: '/members',
      associationId: 'association-1',
    })
  })

  it('uses exact paths and methods for every collection endpoint', async () => {
    const fetchMock = vi.fn().mockImplementation(async () =>
      Response.json({ code: 'OK', data: {} }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const { platformApi } = await loadApi()

    await platformApi.associationDashboard()
    await platformApi.enterpriseDashboard()
    await platformApi.members()
    await platformApi.memberImportPreview('batch /一')
    await platformApi.commitMemberImport('batch /一')
    await platformApi.policies()
    await platformApi.matches()
    await platformApi.collaborations()
    await platformApi.notifications({ status: 'ARCHIVED', page: 2, size: 15 })
    await platformApi.markNotificationRead('message /一')
    await platformApi.archiveNotification('message /一')
    await platformApi.restoreNotification('message /一')

    const calls = fetchMock.mock.calls.map(([url, init]) => ({
      url,
      method: (init as RequestInit).method ?? 'GET',
    }))
    expect(calls).toEqual([
      { url: '/api/v1/dashboards/association', method: 'GET' },
      { url: '/api/v1/dashboards/enterprise', method: 'GET' },
      { url: '/api/v1/members/page?q=&status=&page=0&size=20&includeDeleted=false', method: 'GET' },
      { url: '/api/v1/members/imports/batch%20%2F%E4%B8%80', method: 'GET' },
      { url: '/api/v1/members/imports/batch%20%2F%E4%B8%80/commit', method: 'POST' },
      { url: '/api/v1/policies/page?q=&level=&page=0&size=20&includeDeleted=false', method: 'GET' },
      { url: '/api/v1/matches?page=0&size=20', method: 'GET' },
      { url: '/api/v1/collaborations/page?query=&stage=&page=0&size=20&includeDeleted=false', method: 'GET' },
      { url: '/api/v1/notifications/messages?unreadOnly=false&page=2&size=15&status=ARCHIVED', method: 'GET' },
      { url: '/api/v1/notifications/messages/message%20%2F%E4%B8%80/read', method: 'PUT' },
      { url: '/api/v1/notifications/messages/message%20%2F%E4%B8%80/archive', method: 'PUT' },
      { url: '/api/v1/notifications/messages/message%20%2F%E4%B8%80/restore', method: 'PUT' },
    ])
    expect(fetchMock.mock.calls.map(([, init]) => (init as RequestInit).body)).toEqual(
      Array(12).fill(undefined),
    )
  })

  it('uses exact create, review, preview and template contracts', async () => {
    const created = { ...profile, version: 0 }
    const reviewed = { ...profile, version: 1 }
    const preview = { batchId: 'batch-2', rows: [] }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ code: 'OK', data: created }, { headers: { ETag: '"0"' } }))
      .mockResolvedValueOnce(Response.json({ code: 'OK', data: reviewed }, { headers: { ETag: '"1"' } }))
      .mockResolvedValueOnce(Response.json({ code: 'OK', data: preview }))
      .mockResolvedValueOnce(new Response(new Uint8Array([1])))
    vi.stubGlobal('fetch', fetchMock)
    const { platformApi } = await loadApi()
    const payload: MemberUpsertPayload = {
      name: profile.name, unifiedSocialCreditCode: profile.unifiedSocialCreditCode, category: profile.category,
      address: profile.address, contactName: profile.contactName, contactPhone: profile.contactPhone,
      introduction: profile.introduction, capabilities: profile.capabilities, products: profile.products,
      cooperationNeeds: profile.cooperationNeeds, visibility: profile.visibility, status: profile.status,
    }
    const file = new File(['xlsx'], 'survey.xlsx')

    await platformApi.createMember(payload)
    await platformApi.reviewMember('id /一', 'INCOMPLETE', '   ', '"0"')
    await platformApi.previewMemberImport(file)
    await platformApi.downloadMemberTemplate()

    const [createUrl, createInit] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(createUrl).toBe('/api/v1/members')
    expect(createInit.method).toBe('POST')
    expect(createInit.body).toBe(JSON.stringify(payload))

    const [reviewUrl, reviewInit] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(reviewUrl).toBe('/api/v1/members/id%20%2F%E4%B8%80/review')
    expect(reviewInit.method).toBe('PUT')
    expect(JSON.parse(String(reviewInit.body))).toEqual({ decision: 'INCOMPLETE', comment: null })

    const [previewUrl, previewInit] = fetchMock.mock.calls[2] as [string, RequestInit]
    expect(previewUrl).toBe('/api/v1/members/imports/preview')
    expect(previewInit.method).toBe('POST')
    const form = previewInit.body as FormData
    expect(form.get('file')).toBeInstanceOf(File)
    expect((form.get('file') as File).name).toBe('survey.xlsx')

    expect(fetchMock.mock.calls[3]?.[0]).toBe('/api/v1/members/import-template')
  })

  it('uses server-side match paging, raw state filters and encoded detail paths', async () => {
    const fetchMock = vi.fn().mockImplementation(async () =>
      Response.json({ code: 'OK', data: { items: [], total: 0, page: 0, size: 20 } }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const { platformApi } = await loadApi()

    await platformApi.matches(2, 50, 'PARTIALLY_CONFIRMED')
    await platformApi.match('match /一')

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/matches?page=2&size=50&state=PARTIALLY_CONFIRMED',
      '/api/v1/matches/match%20%2F%E4%B8%80',
    ])
  })

  it('uses server-side audit paging inside the selected enterprise scope', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      Response.json({ code: 'OK', data: { items: [], total: 0, page: 2, size: 50, snapshotId: 99 } }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const { platformApi } = await loadApi()

    await platformApi.auditLogPage('enterprise /一', 2, 50, 99)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/audit-logs/page?enterpriseId=enterprise%20%2F%E4%B8%80&page=2&size=50&snapshotId=99')
    expect(init.method ?? 'GET').toBe('GET')
  })

  it('uses versioned policy lifecycle, notification publish and subscription contracts', async () => {
    const policy = {
      id: 'policy /一', title: '政策标题', summary: '政策摘要', associationId: 'association-1', version: 7,
    } as import('../types/domain').Policy
    const subscription = {
      id: 'subscription /一', version: 3, status: 'ACTIVE', subscriptionType: 'POLICY',
      filters: {}, channels: ['IN_APP'],
    } as import('../types/domain').Subscription
    const fetchMock = vi.fn().mockImplementation(async () => Response.json({ code: 'OK', data: {} }))
    vi.stubGlobal('fetch', fetchMock)
    const { platformApi } = await loadApi()

    await platformApi.policy(policy.id, true)
    await platformApi.updatePolicy(policy.id, {} as import('../types/domain').PolicyUpsertPayload, 7)
    await platformApi.disablePolicy(policy)
    await platformApi.deletePolicy(policy)
    await platformApi.restorePolicy(policy)
    await platformApi.policyHistory(policy.id, 25)
    await platformApi.updateSubscription(subscription, { subscriptionType: 'POLICY', filters: {}, channels: ['IN_APP'] })
    await platformApi.publishPolicyNotification(policy)

    expect(fetchMock.mock.calls.map(([url, init]) => ({
      url,
      method: (init as RequestInit).method ?? 'GET',
      ifMatch: new Headers((init as RequestInit).headers).get('If-Match'),
    }))).toEqual([
      { url: '/api/v1/policies/policy%20%2F%E4%B8%80?includeDeleted=true', method: 'GET', ifMatch: null },
      { url: '/api/v1/policies/policy%20%2F%E4%B8%80', method: 'PUT', ifMatch: '"7"' },
      { url: '/api/v1/policies/policy%20%2F%E4%B8%80/disable', method: 'PUT', ifMatch: '"7"' },
      { url: '/api/v1/policies/policy%20%2F%E4%B8%80', method: 'DELETE', ifMatch: '"7"' },
      { url: '/api/v1/policies/policy%20%2F%E4%B8%80/restore', method: 'PUT', ifMatch: '"7"' },
      { url: '/api/v1/policies/policy%20%2F%E4%B8%80/history?limit=25', method: 'GET', ifMatch: null },
      { url: '/api/v1/notifications/subscriptions/subscription%20%2F%E4%B8%80', method: 'PUT', ifMatch: '"3"' },
      { url: '/api/v1/notifications/policies', method: 'POST', ifMatch: null },
    ])
    expect(JSON.parse(String((fetchMock.mock.calls[7]?.[1] as RequestInit).body))).toMatchObject({
      associationId: 'association-1', policyId: 'policy /一', idempotencyKey: 'policy-release-policy /一-7',
    })
  })

  it('uses filtered paging and strong CAS contracts for the policy impact workflow', async () => {
    const impact = {
      id: 'impact /一', policyDocumentId: 'policy /一', enterpriseId: 'enterprise /一', version: 7,
    } as import('../types/domain').PolicyImpactAnalysis
    const fetchMock = vi.fn().mockImplementation(async () =>
      Response.json({ code: 'OK', data: {} }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const { platformApi } = await loadApi()

    await platformApi.policyImpacts(2, 50, {
      status: 'PENDING_REVIEW',
      policyDocumentId: impact.policyDocumentId,
      enterpriseId: impact.enterpriseId,
    })
    await platformApi.policyImpact(impact.id)
    await platformApi.createPolicyImpact(impact.policyDocumentId, impact.enterpriseId)
    await platformApi.reanalyzePolicyImpact(impact)
    await platformApi.reviewPolicyImpact(impact, false, '  证据需补充  ')
    await platformApi.policyImpactHistory(impact.id, 25)

    expect(fetchMock.mock.calls.map(([url, init]) => ({
      url,
      method: (init as RequestInit).method ?? 'GET',
      ifMatch: new Headers((init as RequestInit).headers).get('If-Match'),
    }))).toEqual([
      {
        url: '/api/v1/policy-impact-analyses/page?page=2&size=50&status=PENDING_REVIEW&policyDocumentId=policy+%2F%E4%B8%80&enterpriseId=enterprise+%2F%E4%B8%80',
        method: 'GET',
        ifMatch: null,
      },
      { url: '/api/v1/policy-impact-analyses/impact%20%2F%E4%B8%80', method: 'GET', ifMatch: null },
      { url: '/api/v1/policy-impact-analyses', method: 'POST', ifMatch: null },
      { url: '/api/v1/policy-impact-analyses/impact%20%2F%E4%B8%80/reanalyze', method: 'PUT', ifMatch: '"7"' },
      { url: '/api/v1/policy-impact-analyses/impact%20%2F%E4%B8%80/review', method: 'PUT', ifMatch: '"7"' },
      { url: '/api/v1/policy-impact-analyses/impact%20%2F%E4%B8%80/history?limit=25', method: 'GET', ifMatch: null },
    ])
    expect(JSON.parse(String((fetchMock.mock.calls[2]?.[1] as RequestInit).body))).toEqual({
      policyDocumentId: 'policy /一', enterpriseId: 'enterprise /一',
    })
    expect(JSON.parse(String((fetchMock.mock.calls[4]?.[1] as RequestInit).body))).toEqual({
      approved: false, comment: '证据需补充',
    })
  })

  it('sends strong CAS versions for match transitions and feedback replacement', async () => {
    const match = {
      id: 'match-1',
      candidateEnterpriseId: 'enterprise-2',
      version: 7,
    } as import('../types/domain').PersistedMatch
    const feedback = { id: 'feedback-1', version: 3 } as import('../types/domain').MatchFeedback
    const fetchMock = vi.fn().mockImplementation(async () =>
      Response.json({ code: 'OK', data: {} }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const { platformApi } = await loadApi()

    await platformApi.transitionMatch(match, 'confirm')
    await platformApi.closeMatch(match, '需求变化')
    await platformApi.inviteMatch(match, 'ENTERPRISE', '请确认', null)
    await platformApi.submitMatchFeedback(match, {
      rating: 5,
      outcome: 'SUCCESS',
      closeReason: null,
      comment: '已达成合作',
    }, feedback)

    expect(fetchMock.mock.calls.map(([url, init]) => ({
      url,
      method: (init as RequestInit).method,
      ifMatch: new Headers((init as RequestInit).headers).get('If-Match'),
    }))).toEqual([
      { url: '/api/v1/matches/match-1/confirm', method: 'POST', ifMatch: '"7"' },
      { url: '/api/v1/matches/match-1/close', method: 'POST', ifMatch: '"7"' },
      { url: '/api/v1/matches/match-1/invitations', method: 'POST', ifMatch: '"7"' },
      { url: '/api/v1/matches/match-1/feedback', method: 'POST', ifMatch: '"3"' },
    ])
  })

  it('does not send If-Match when creating a participant feedback row for the first time', async () => {
    const match = { id: 'match-1', version: 7 } as import('../types/domain').PersistedMatch
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ code: 'OK', data: {} }))
    vi.stubGlobal('fetch', fetchMock)
    const { platformApi } = await loadApi()

    await platformApi.submitMatchFeedback(match, {
      rating: null,
      outcome: 'NO_DEAL',
      closeReason: '预算变化',
      comment: null,
    }, null)

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new Headers(init.headers).has('If-Match')).toBe(false)
  })

  it('encodes policy level before pagination and loads visible level facets', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({
        code: 'OK',
        data: { items: [], total: 0, page: 2, size: 25 },
      }))
      .mockResolvedValueOnce(Response.json({ code: 'OK', data: ['国家级', '北京市/行业'] }))
    vi.stubGlobal('fetch', fetchMock)
    const { platformApi } = await loadApi()

    await platformApi.policies('安全 管理', 2, 25, true, '北京市/行业')
    await expect(platformApi.policyLevels()).resolves.toEqual(['国家级', '北京市/行业'])

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/policies/page?q=%E5%AE%89%E5%85%A8%20%E7%AE%A1%E7%90%86&level=%E5%8C%97%E4%BA%AC%E5%B8%82%2F%E8%A1%8C%E4%B8%9A&page=2&size=25&includeDeleted=true',
      '/api/v1/policies/levels',
    ])
  })

  it.each(['7', '"07"', 'x"7"', '"7"x', '"-1"', '', 'W/"0"', 'W/"7"'])('rejects invalid strong ETag %j before sending a write', async (etag) => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { platformApi } = await loadApi()

    await expect(platformApi.updateMember(profile.id, {} as MemberUpsertPayload, etag)).rejects.toMatchObject({
      code: 'INVALID_ETAG',
      requestId: 'member-etag-client',
    })
    await expect(platformApi.reviewMember(profile.id, 'ACTIVE', '', etag)).rejects.toMatchObject({
      code: 'INVALID_ETAG',
      requestId: 'member-etag-client',
    })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('does not trust an unsafe request ID when a response omits ETag', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      Response.json({ code: 'OK', data: profile }, { headers: { 'X-Request-Id': 'unsafe/request' } }),
    ))
    const { platformApi } = await loadApi()

    await expect(platformApi.member(profile.id)).rejects.toMatchObject({
      code: 'MISSING_ETAG',
      requestId: 'member-etag-contract',
    })
  })

  it('propagates network failures instead of returning demo records', async () => {
    const failure = new TypeError('offline')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(failure))
    const { platformApi } = await loadApi()

    await expect(platformApi.associationDashboard()).rejects.toBe(failure)
    await expect(platformApi.members()).rejects.toBe(failure)
  })
})
