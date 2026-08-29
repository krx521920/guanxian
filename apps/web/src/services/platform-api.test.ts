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
    await platformApi.notifications(true, 2, 15)
    await platformApi.markNotificationRead('message /一')

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
      { url: '/api/v1/policies/page?q=&page=0&size=20&includeDeleted=false', method: 'GET' },
      { url: '/api/v1/matches', method: 'GET' },
      { url: '/api/v1/collaborations/page?query=&page=0&size=20&includeDeleted=false', method: 'GET' },
      { url: '/api/v1/notifications/messages?unreadOnly=true&page=2&size=15', method: 'GET' },
      { url: '/api/v1/notifications/messages/message%20%2F%E4%B8%80/read', method: 'PUT' },
    ])
    expect(fetchMock.mock.calls.map(([, init]) => (init as RequestInit).body)).toEqual(
      Array(10).fill(undefined),
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

  it.each(['7', '"07"', 'x"7"', '"7"x', '"-1"', ''])('rejects invalid strong ETag %j before sending a write', async (etag) => {
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
