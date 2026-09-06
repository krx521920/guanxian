import { beforeEach, describe, expect, it, vi } from 'vitest'
import { profileApi, profileDiff, publicPreview, publicEnterprises } from './profile-workflow'
import type { MemberProfile, MemberUpsertPayload } from '../types/domain'
const { request } = vi.hoisted(() => ({ request: vi.fn() }))
vi.mock('./http', () => ({ request }))
const profile = { id:'enterprise',name:'企业',category:'类别',address:'内部地址',contactName:'内部人',contactPhone:'内部电话',
  contactEmail:'internal@example.test',introduction:'简介',capabilities:[],products:[],services:[],applicationScenarios:[],cooperationNeeds:['内部需求'],
  associationId:'association',unifiedSocialCreditCode:'CREDIT',version:7,status:'ACTIVE',visibility:'PUBLIC' } as unknown as MemberProfile
beforeEach(() => { vi.clearAllMocks(); vi.unstubAllGlobals() })
describe('profile draft and publication contract', () => {
  it('saves a draft with separate official and workflow versions, not the legacy main record endpoint', async () => {
    await profileApi.save('enterprise',3,7,profile as MemberUpsertPayload)
    expect(request).toHaveBeenCalledWith('/enterprise-profiles/enterprise/draft',expect.objectContaining({method:'PUT',headers:{'If-Match':'"3"'},body:JSON.stringify({content:profile,baseVersion:7})}))
  })
  it('requires safe versions for every transition', () => {
    for (const version of [-1,1.5,NaN,Number.MAX_SAFE_INTEGER+1]) expect(() => profileApi.action('id',version,'publish')).toThrow('版本无效')
  })
  it('submits, reviews, consents, publishes and withdraws as separate versioned actions', async () => {
    for (const action of ['submit','review','consent','publish','withdraw'] as const) {
      await profileApi.action('id',4,action,{note:'说明'})
      expect(request).toHaveBeenLastCalledWith(`/enterprise-profiles/id/${action}`,expect.objectContaining({method:'POST',headers:{'If-Match':'"4"'}}))
    }
  })
  it('shows internal changes in reviewer diffs but never in public preview', () => {
    const draft={...profile,contactPhone:'待审电话',introduction:'待审简介'}
    expect(profileDiff(profile,draft).map(item=>item.key)).toEqual(['contactPhone','introduction'])
    expect(Object.keys(publicPreview(profile))).toEqual(['name','category','introduction','capabilities','products','services','applicationScenarios'])
    expect(JSON.stringify(publicPreview(profile))).not.toContain('内部')
  })
  it('anonymous directory uses a separate transport with no cookies, authorization or system context', async () => {
    const fetch=vi.fn().mockResolvedValue({ok:true,json:async()=>({code:'OK',data:[]})});vi.stubGlobal('fetch',fetch)
    expect(await publicEnterprises('企业',1)).toEqual([])
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/public/enterprises?q='),expect.objectContaining({credentials:'omit',cache:'no-store'}))
    expect(fetch.mock.calls[0][1].headers).toBeUndefined();expect(request).not.toHaveBeenCalled()
  })
})
