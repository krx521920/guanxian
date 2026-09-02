import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Collaboration, Demand, MatchFeedback, MatchInvitation, Offering, PersistedMatch } from '../types/domain'

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

describe('persisted workflow API contract', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_API_BASE_URL', '/api/v1')
    vi.stubGlobal('window', browserWindow)
    vi.stubGlobal('crypto', { randomUUID: () => 'workflow-request-id' })
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('loads persisted match records from the real collection endpoint', async () => {
    const page = { items: [], total: 0, page: 0, size: 20 }
    const { platformApi, fetchMock } = await apiWith(page)
    await expect(platformApi.matches()).resolves.toEqual(page)
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/matches?page=0&size=20')
  })

  it('generates and stores matches only after an explicit POST', async () => {
    const { platformApi, fetchMock } = await apiWith([])
    await platformApi.generateMatches('demand-1', 5)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/matches/demand/demand-1/generate')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ limit: 5 })
  })

  it('loads only demands the backend authorizes for match generation', async () => {
    const page = { items: [], total: 0, page: 2, size: 50 }
    const { platformApi, fetchMock } = await apiWith(page)
    await expect(platformApi.matchGenerationDemands(2, 50)).resolves.toEqual(page)
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/matches/generation-demands?page=2&size=50')
  })

  it('sends persisted match versions as strong If-Match values', async () => {
    const match = { id: 'match-1', version: 9 } as PersistedMatch
    const { platformApi, fetchMock } = await apiWith(match)
    await platformApi.transitionMatch(match, 'recommend')
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('If-Match')).toBe('"9"')
  })

  it('keeps the originating match id when a collaboration is created', async () => {
    const result = { id: 'collaboration-1' } as Collaboration
    const { platformApi, fetchMock } = await apiWith(result)
    await platformApi.createCollaboration({
      title: '联合测试', participants: ['甲', '乙'], owner: null, priority: '中', nextAction: null,
      dueDate: null, progress: 0, matchId: 'match-1',
    })
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(String(init.body))).toMatchObject({ matchId: 'match-1' })
  })

  it('closes an open demand with its current version and an archived reason', async () => {
    const demand = { id: 'demand-1', version: 4 } as Demand
    const { platformApi, fetchMock } = await apiWith(demand)
    await platformApi.closeDemand(demand, '需求已完成')
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/demands/demand-1/close')
    expect(new Headers(init.headers).get('If-Match')).toBe('"4"')
    expect(JSON.parse(String(init.body))).toEqual({ reason: '需求已完成' })
  })

  it('soft deletes, restores, and re-enables catalog records with strong versions', async () => {
    const offering = { id: 'offering /一', version: 4 } as Offering
    const demand = { id: 'demand /一', version: 6 } as Demand
    const { platformApi, fetchMock } = await apiWith({})

    await platformApi.deleteOffering(offering)
    await platformApi.restoreOffering(offering)
    await platformApi.enableOffering(offering)
    await platformApi.deleteDemand(demand)
    await platformApi.restoreDemand(demand)
    await platformApi.enableDemand(demand)

    expect(fetchMock.mock.calls.map(([url, init]) => ({
      url,
      method: (init as RequestInit).method,
      ifMatch: new Headers((init as RequestInit).headers).get('If-Match'),
    }))).toEqual([
      { url: '/api/v1/offerings/offering%20%2F%E4%B8%80', method: 'DELETE', ifMatch: '"4"' },
      { url: '/api/v1/offerings/offering%20%2F%E4%B8%80/restore', method: 'POST', ifMatch: '"4"' },
      { url: '/api/v1/offerings/offering%20%2F%E4%B8%80/enable', method: 'POST', ifMatch: '"4"' },
      { url: '/api/v1/demands/demand%20%2F%E4%B8%80', method: 'DELETE', ifMatch: '"6"' },
      { url: '/api/v1/demands/demand%20%2F%E4%B8%80/restore', method: 'POST', ifMatch: '"6"' },
      { url: '/api/v1/demands/demand%20%2F%E4%B8%80/enable', method: 'POST', ifMatch: '"6"' },
    ])
  })

  it('creates an invitation for the persisted candidate enterprise', async () => {
    const match = { id: 'match/1', candidateEnterpriseId: 'enterprise-2', version: 7 } as PersistedMatch
    const { platformApi, fetchMock } = await apiWith({ id: 'invitation-1' })
    await platformApi.inviteMatch(match, 'ASSOCIATION_RECOMMENDATION', '建议对接', '2026-09-01T08:00:00.000Z')
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/matches/match%2F1/invitations')
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('If-Match')).toBe('"7"')
    expect(JSON.parse(String(init.body))).toEqual({
      recipientEnterpriseId: 'enterprise-2',
      invitationType: 'ASSOCIATION_RECOMMENDATION',
      message: '建议对接',
      expiresAt: '2026-09-01T08:00:00.000Z',
    })
  })

  it('responds to an invitation with its current version', async () => {
    const invitation = { id: 'invitation-1', version: 3 } as MatchInvitation
    const { platformApi, fetchMock } = await apiWith(invitation)
    await platformApi.respondMatchInvitation(invitation, true, '安排技术交流')
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/matches/invitations/invitation-1/respond')
    expect(new Headers(init.headers).get('If-Match')).toBe('"3"')
    expect(JSON.parse(String(init.body))).toEqual({ accepted: true, comment: '安排技术交流' })
  })

  it('persists negotiation records on the selected match', async () => {
    const { platformApi, fetchMock } = await apiWith({ id: 'negotiation-1' })
    const match = { id: 'match-1', version: 4 } as PersistedMatch
    const payload = { stage: 'TECHNICAL_EXCHANGE' as const, summary: '完成方案评审', nextAction: '现场勘查', nextActionAt: null }
    await platformApi.addMatchNegotiation(match, payload)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/matches/match-1/negotiations')
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('If-Match')).toBe('"4"')
    expect(JSON.parse(String(init.body))).toEqual(payload)
  })

  it('submits participant feedback without fabricating a local result', async () => {
    const result = { id: 'feedback-1', outcome: 'SUCCESS' }
    const match = { id: 'match-1', version: 8 } as PersistedMatch
    const { platformApi, fetchMock } = await apiWith(result)
    await expect(platformApi.submitMatchFeedback(match, {
      rating: 5, outcome: 'SUCCESS', closeReason: null, comment: '合作达成',
    })).resolves.toEqual(result)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/matches/match-1/feedback')
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).has('If-Match')).toBe(false)
  })

  it('updates participant feedback with the persisted feedback version', async () => {
    const match = { id: 'match-1', version: 8 } as PersistedMatch
    const feedback = { id: 'feedback-1', version: 3 } as MatchFeedback
    const { platformApi, fetchMock } = await apiWith({ ...feedback, version: 4 })
    await platformApi.submitMatchFeedback(match, {
      rating: 4, outcome: 'SUCCESS', closeReason: null, comment: '更新评价',
    }, feedback)
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new Headers(init.headers).get('If-Match')).toBe('"3"')
  })

  it('loads and archives persisted outcomes', async () => {
    const { platformApi, fetchMock } = await apiWith([])
    const match = { id: 'match-1', version: 8 } as PersistedMatch
    await platformApi.matchOutcomes('match-1')
    await platformApi.archiveMatchOutcome(match, {
      title: '试点落地', summary: '完成现场部署', contractAmount: 120000,
      resultType: 'PILOT', visibility: 'ASSOCIATION',
    })
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/matches/match-1/outcomes')
    const [url, init] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(url).toBe('/api/v1/matches/match-1/outcomes')
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('If-Match')).toBe('"8"')
    expect(JSON.parse(String(init.body))).toMatchObject({ title: '试点落地', visibility: 'ASSOCIATION' })
  })
})
