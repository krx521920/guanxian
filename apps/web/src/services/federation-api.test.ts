import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  AssociationAccessRequest,
  AssociationConsent,
  AssociationRecommendation,
  AssociationRelationship,
  AssociationSharePolicy,
  AssociationSharePolicyPayload,
  Demand,
  EcosystemMatch,
  Offering,
  PersistedMatch,
} from '../types/domain'

const redactedPartnerFields = [
  ({ enterpriseName: null } satisfies Pick<Offering, 'enterpriseName'>).enterpriseName,
  ({ enterpriseName: null } satisfies Pick<Demand, 'enterpriseName'>).enterpriseName,
  ({ updatedAt: null } satisfies Pick<EcosystemMatch, 'updatedAt'>).updatedAt,
  ({ updatedAt: null } satisfies Pick<PersistedMatch, 'updatedAt'>).updatedAt,
]

const browserWindow = {
  setTimeout: globalThis.setTimeout.bind(globalThis),
  clearTimeout: globalThis.clearTimeout.bind(globalThis),
}

async function apiWith(data: unknown) {
  vi.resetModules()
  const fetchMock = vi.fn().mockImplementation(async () => Response.json({ code: 'OK', data }))
  vi.stubGlobal('fetch', fetchMock)
  const { platformApi } = await import('./platform-api')
  return { platformApi, fetchMock }
}

describe('cross-association API contract', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_API_BASE_URL', '/api/v1')
    vi.stubGlobal('window', browserWindow)
    vi.stubGlobal('crypto', { randomUUID: () => 'federation-request-id' })
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('lets only the server cancel a persisted pending access request', async () => {
    const item = { id: 'request/1', version: 3 } as AssociationAccessRequest
    const { platformApi, fetchMock } = await apiWith(item)
    await platformApi.cancelAssociationAccessRequest(item, '项目暂停')

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/cross-associations/access-requests/request%2F1/cancel')
    expect(init.method).toBe('PUT')
    expect(new Headers(init.headers).get('If-Match')).toBe('"3"')
    expect(JSON.parse(String(init.body))).toEqual({ reason: '项目暂停' })
  })

  it('uses server pagination for every federation management ledger', async () => {
    const { platformApi, fetchMock } = await apiWith({ items: [], total: 0, page: 2, size: 10 })
    await platformApi.associationAccessRequestPage(2, 10)
    await platformApi.associationRelationshipPage(2, 10)
    await platformApi.associationSharePolicyPage(2, 10)
    await platformApi.associationConsentPage(2, 10)
    await platformApi.associationRecommendationPage(2, 10)

    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      '/api/v1/cross-associations/access-requests/page?page=2&size=10',
      '/api/v1/cross-associations/relationships/page?page=2&size=10',
      '/api/v1/cross-associations/share-policies/page?page=2&size=10',
      '/api/v1/cross-associations/consents/page?page=2&size=10',
      '/api/v1/cross-associations/recommendations/page?page=2&size=10',
    ])
  })

  it('sends bilateral approval scope and an explicit authorization deadline', async () => {
    const item = { id: 'request-1', version: 4 } as AssociationAccessRequest
    const { platformApi, fetchMock } = await apiWith(item)
    await platformApi.reviewAssociationAccessRequest(item, {
      approved: true,
      comment: '同意接入',
      relationshipExpiresAt: '2027-08-30T00:00:00.000Z',
      allowMemberData: true,
    })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/cross-associations/access-requests/request-1/review')
    expect(init.method).toBe('PUT')
    expect(new Headers(init.headers).get('If-Match')).toBe('"4"')
    expect(JSON.parse(String(init.body))).toEqual({
      decision: 'APPROVE',
      comment: '同意接入',
      relationshipExpiresAt: '2027-08-30T00:00:00.000Z',
      allowMemberData: true,
    })
  })

  it('cannot extend a relationship while resuming it', async () => {
    const item = {
      sourceAssociationId: 'association-a',
      targetAssociationId: 'association-b',
      expiresAt: '2027-08-30T00:00:00.000Z',
      version: 6,
    } as AssociationRelationship
    const { platformApi, fetchMock } = await apiWith(item)
    await platformApi.changeAssociationRelationship(item, 'ACTIVATE', '恢复原授权')

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/cross-associations/relationships/association-a/association-b')
    expect(new Headers(init.headers).get('If-Match')).toBe('"6"')
    expect(JSON.parse(String(init.body))).toEqual({
      action: 'ACTIVATE',
      expiresAt: null,
      reason: '恢复原授权',
    })
  })

  it('drops relationship scope when an access request is rejected', async () => {
    const item = { id: 'request-2', version: 5 } as AssociationAccessRequest
    const { platformApi, fetchMock } = await apiWith(item)
    await platformApi.reviewAssociationAccessRequest(item, {
      approved: false,
      comment: '暂不符合条件',
      relationshipExpiresAt: '2027-08-30T00:00:00.000Z',
      allowMemberData: true,
    })

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(String(init.body))).toEqual({
      decision: 'REJECT',
      comment: '暂不符合条件',
      relationshipExpiresAt: null,
      allowMemberData: false,
    })
  })

  it('persists only selected share-policy fields', async () => {
    const payload: AssociationSharePolicyPayload = {
      sourceAssociationId: 'association-a',
      targetAssociationId: 'association-b',
      resourceType: 'MEMBER',
      visibleFields: ['name', 'introduction'],
      validFrom: '2026-08-30T00:00:00.000Z',
      expiresAt: '2027-08-30T00:00:00.000Z',
      status: 'ACTIVE',
    }
    const { platformApi, fetchMock } = await apiWith({ id: 'policy-1' })
    await platformApi.createAssociationSharePolicy(payload)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/cross-associations/share-policies')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual(payload)
  })

  it('uses strong version checks when suspending a field policy', async () => {
    const item = { id: 'policy/1', version: 4 } as AssociationSharePolicy
    const { platformApi, fetchMock } = await apiWith(item)
    await platformApi.changeAssociationSharePolicyStatus(item, 'SUSPENDED')

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/cross-associations/share-policies/policy%2F1/status')
    expect(init.method).toBe('PUT')
    expect(new Headers(init.headers).get('If-Match')).toBe('"4"')
    expect(JSON.parse(String(init.body))).toEqual({ status: 'SUSPENDED' })
  })

  it('reviews a persisted recommendation with its current version', async () => {
    const item = { id: 'recommendation-1', version: 8 } as AssociationRecommendation
    const { platformApi, fetchMock } = await apiWith(item)
    await platformApi.reviewAssociationRecommendation(item, false, '资料不完整')

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/cross-associations/recommendations/recommendation-1/review')
    expect(init.method).toBe('PUT')
    expect(new Headers(init.headers).get('If-Match')).toBe('"8"')
    expect(JSON.parse(String(init.body))).toEqual({ decision: 'REJECT', comment: '资料不完整' })
  })

  it('loads only server-authorized enterprise consent targets', async () => {
    const targets = [{ targetAssociationId: 'association-b', resourceType: 'PRODUCT', policyExpiresAt: null }]
    const { platformApi, fetchMock } = await apiWith(targets)
    await expect(platformApi.associationConsentTargets()).resolves.toEqual(targets)
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/cross-associations/consent-targets')
    expect(redactedPartnerFields).toEqual([null, null, null, null])
  })

  it('grants and revokes persisted resource consent without local simulation', async () => {
    const consent = { id: 'consent/1', version: 9 } as AssociationConsent
    const { platformApi, fetchMock } = await apiWith(consent)
    await platformApi.grantAssociationConsent({
      enterpriseId: null,
      targetAssociationId: 'association-b',
      resourceType: 'PRODUCT',
      resourceId: 'product-1',
      expiresAt: '2027-08-30T00:00:00.000Z',
    })
    await platformApi.revokeAssociationConsent(consent)

    const [grantUrl, grantInit] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(grantUrl).toBe('/api/v1/cross-associations/consents')
    expect(grantInit.method).toBe('POST')
    expect(JSON.parse(String(grantInit.body))).toMatchObject({
      targetAssociationId: 'association-b', resourceType: 'PRODUCT', resourceId: 'product-1',
    })
    const [revokeUrl, revokeInit] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(revokeUrl).toBe('/api/v1/cross-associations/consents/consent%2F1')
    expect(revokeInit.method).toBe('DELETE')
    expect(new Headers(revokeInit.headers).get('If-Match')).toBe('"9"')
  })
})
