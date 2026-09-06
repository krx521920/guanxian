import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enterpriseTeamApi, type TeamMember } from './enterprise-team'
import { platformApi } from './platform-api'

describe('enterprise self-service transport', () => {
  beforeEach(() => vi.stubGlobal('window', { setTimeout: globalThis.setTimeout, clearTimeout: globalThis.clearTimeout }))
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
  it('never lets a browser choose enterprise or grant role when inviting', async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({ code: 'OK', data: {} })); vi.stubGlobal('fetch', fetch)
    await enterpriseTeamApi.invite(' team.user ')
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/my-enterprise/team/invitations')
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ username: 'team.user' })
  })
  it('sends exact version and reason, never a password, for a member suspension', async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({ code: 'OK', data: null })); vi.stubGlobal('fetch', fetch)
    await enterpriseTeamApi.disable({ id: 'member', version: 3 } as TeamMember, ' 离职 ')
    expect(fetch.mock.calls[0][1].headers.get('If-Match')).toBe('"3"')
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ note: '离职' })
    for (const version of [-1, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
      expect(() => enterpriseTeamApi.disable({ id: 'member', version } as TeamMember, 'test')).toThrow('版本无效')
    }
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('requests backend own-only filtering for both totals and pages', async () => {
    const fetch = vi.fn().mockImplementation(async () => Response.json({ code: 'OK', data: {} })); vi.stubGlobal('fetch', fetch)
    await platformApi.offerings('探测', false, 2, 20, true)
    await platformApi.demands('管线', false, 3, 20, true)
    for (const [url] of fetch.mock.calls) {
      expect(url).toContain('ownOnly=true'); expect(url).not.toContain('enterpriseId=')
    }
    expect(fetch.mock.calls[0][0]).toContain('page=2')
    expect(fetch.mock.calls[1][0]).toContain('page=3')
  })
})
