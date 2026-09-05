import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { captureInvitation, invitationApi, myEnterpriseApi } from './enterprise-onboarding'
import type { MemberUpsertPayload } from '../types/domain'

function storage(): Storage {
  const data = new Map<string,string>()
  return { get length() { return data.size }, clear: () => data.clear(), key: i => [...data.keys()][i] || null,
    getItem: k => data.get(k) ?? null, setItem: (k,v) => { data.set(k,v) }, removeItem: k => { data.delete(k) } }
}
describe('enterprise onboarding boundaries', () => {
  beforeEach(() => {
    vi.stubGlobal('sessionStorage',storage())
    vi.stubGlobal('window',{ setTimeout:globalThis.setTimeout, clearTimeout:globalThis.clearTimeout })
  })
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
  it('captures a valid fragment across the login redirect but rejects malformed and expired invitations', () => {
    const token='a'.repeat(43), local=storage()
    expect(captureInvitation('#invite='+token,local)).toBe(token)
    expect(captureInvitation('',local)).toBe(token)
    expect(captureInvitation('#invite=bad',local)).toBe('')
    expect(captureInvitation('',local)).toBe('')
    captureInvitation('#invite='+token,local)
    vi.spyOn(Date,'now').mockReturnValue(Date.now()+73*3600*1000)
    expect(captureInvitation('',local)).toBe('')
  })
  it('can use a valid link in memory when storage is unavailable', () => {
    const local=storage(); vi.spyOn(local,'removeItem').mockImplementation(()=>{ throw new Error('blocked') })
    expect(captureInvitation('#invite='+'z'.repeat(43),local)).toBe('z'.repeat(43))
    expect(captureInvitation('',local)).toBe('')
  })
  it('never places the invitation capability in an API URL', async () => {
    const fetch = vi.fn().mockImplementation(async()=>Response.json({code:'OK',data:{status:'CLAIMED'}})); vi.stubGlobal('fetch',fetch)
    const token='p'.repeat(43)
    await invitationApi.preview(token); await invitationApi.claim(token)
    for (const [url, init] of fetch.mock.calls) { expect(String(url)).not.toContain(token); expect(init.method).toBe('POST'); expect(init.body).toContain(token) }
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ token,confirmed:true })
  })
  it('sends exact optimistic versions on invitation decisions', async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({code:'OK',data:{status:'APPROVED'}})); vi.stubGlobal('fetch',fetch)
    await invitationApi.review({ id:'invitation',version:4 } as Parameters<typeof invitationApi.review>[0],'APPROVE','人工核验')
    expect(fetch.mock.calls[0][1].headers.get('If-Match')).toBe('"4"')
  })
  it('my-enterprise never accepts a path target and requires the server strong ETag', async () => {
    const fetch = vi.fn().mockImplementation(async()=>Response.json({code:'OK',data:{profile:{id:'own'},canEdit:true}},{headers:{ETag:'"5"'}})); vi.stubGlobal('fetch',fetch)
    expect((await myEnterpriseApi.get()).etag).toBe('"5"')
    await myEnterpriseApi.update({ name:'existing',category:'service' } as MemberUpsertPayload,'"5"')
    expect(fetch.mock.calls[1][0]).toBe('/api/v1/my-enterprise')
    expect(fetch.mock.calls[1][1].headers.get('If-Match')).toBe('"5"')
    expect(()=>myEnterpriseApi.update({} as MemberUpsertPayload,'*')).toThrow()
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it.each(['', 'W/"1"','*'])('refuses a missing/weak my-enterprise version %s', async etag => {
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(Response.json({code:'OK',data:{profile:{},canEdit:true}},{headers:{ETag:etag}})))
    await expect(myEnterpriseApi.get()).rejects.toThrow('有效资料版本')
  })
})
