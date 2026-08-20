import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MemberProfile, MemberUpsertPayload } from '../types/domain'

const browserWindow = {
  setTimeout: globalThis.setTimeout.bind(globalThis),
  clearTimeout: globalThis.clearTimeout.bind(globalThis),
}

const profile: MemberProfile = {
  id: '20000000-0000-0000-0000-000000000001',
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
  status: 'ACTIVE',
  version: 7,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-08-20T00:00:00Z',
}

async function loadApi() {
  vi.resetModules()
  vi.stubEnv('VITE_MOCK_FALLBACK', 'false')
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
      status: profile.status,
    }

    await expect(platformApi.updateMember(profile.id, payload, '"7"')).rejects.toMatchObject({
      status: 412,
      code: 'PRECONDITION_FAILED',
      requestId: 'etag-conflict',
    })
  })
})
