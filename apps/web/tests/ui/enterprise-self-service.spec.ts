import { test, expect, type Page } from '@playwright/test'

const associationId='61000000-0000-4000-8000-000000000001', enterpriseId='62000000-0000-4000-8000-000000000001'
const authority='http://127.0.0.1:18188/identity/realms/entry-tests'
async function fixture(page: Page, role: 'ENTERPRISE_ADMIN'|'ENTERPRISE_MEMBER' = 'ENTERPRISE_ADMIN') {
  const state = {
    errors: [] as string[], requests: [] as string[], writes: [] as { path: string; body: Record<string, unknown> | null }[], conflict: false,
    invitation: { id: '63000000-0000-4000-8000-000000000001', enterpriseId, enterpriseName: '虚构·企业自助验证公司', associationName: '虚构协会',
      username:'team.user',status:'ISSUED',version:0,targetRole:'ENTERPRISE_MEMBER',createdAt:'2026-09-05T12:00:00Z',expiresAt:'2099-09-08T12:00:00Z',reviewNote:null },
    invited:false, member:{id:'64000000-0000-4000-8000-000000000001',username:'existing.member',displayName:'测试成员',role:'ENTERPRISE_MEMBER',status:'ACTIVE',version:0,canDisable:true},
    offering: {id:'65000000-0000-4000-8000-000000000001',enterpriseId,enterpriseName:'虚构·企业自助验证公司',name:'本企业测试产品',kind:'PRODUCT',description:'验证专用，非真实产品',scenarios:[],qualifications:[],visibility:'MEMBERS',status:'DRAFT',version:0,disabled:false,deleted:false,updatedAt:'2026-09-05T12:00:00Z',allowedActions:role==='ENTERPRISE_ADMIN'?['UPDATE','SUBMIT']:[]},
  }
  page.on('pageerror', error => state.errors.push(error.message))
  await page.addInitScript(({authority}) => {
    sessionStorage.setItem(`oidc.user:${authority}:entry-tests`, JSON.stringify({access_token:'fixture-only-token',token_type:'Bearer',scope:'openid',expires_at:Math.floor(Date.now()/1000)+3600,profile:{sub:'fixture-owner'}}))
  }, {authority})
  await page.route('**/api/v1/**', async route => {
    const request=route.request(), url=new URL(request.url()), path=url.pathname
    state.requests.push(url.pathname+url.search)
    const ok=(data:unknown)=>route.fulfill({json:{code:'OK',data}})
    const fail=(status:number,message:string)=>route.fulfill({status,json:{code:'TEST_FAILURE',message}})
    if(request.method()!=='GET') state.writes.push({path,body:request.postData()?request.postDataJSON():null})
    if(path==='/api/v1/users/me') return ok({subject:'fixture-owner',username:'owner.user',displayName:'测试负责人',roles:[role],permissions:[],associationId,enterpriseId,organization:'虚构·企业自助验证公司'})
    if(path==='/api/v1/dashboards/enterprise') return ok({completeness:80,metrics:[],matches:[],recommendedPolicies:[]})
    if(path==='/api/v1/my-enterprise/team/members') return ok({items:[state.member],total:1,page:0,size:20})
    if(path.endsWith('/team/invitations') && request.method()==='POST') {
      expect(request.postDataJSON()).toEqual({username:'team.user'});state.invited=true
      return ok({invitation:state.invitation,token:'t'.repeat(43)})
    }
    if(path.endsWith('/team/invitations')) return ok({items:state.invited?[state.invitation]:[],total:state.invited?1:0,page:0,size:20})
    if(path.endsWith('/revoke')) {
      expect(request.headers()['if-match']).toBe('"0"');state.invitation.status='REVOKED';state.invitation.version++
      return ok(state.invitation)
    }
    if(path.endsWith('/disable')) {
      expect(request.postDataJSON()).toEqual({note:'测试离职'})
      if(state.conflict) {state.member.version=1;return fail(412,'成员权限已更新，请刷新后重试')}
      expect(request.headers()['if-match']).toBe(`"${state.member.version}"`)
      state.member.status='INACTIVE';state.member.canDisable=false;state.member.version++
      return ok(null)
    }
    if(path==='/api/v1/offerings') {
      if(request.method()==='POST') Object.assign(state.offering,request.postDataJSON())
      return request.method()==='GET'?ok({items:[state.offering],total:1,page:0,size:20}):ok(state.offering)
    }
    if(path.endsWith('/submit')) {expect(request.headers()['if-match']).toBe('"0"');state.offering.status='PENDING_REVIEW';state.offering.allowedActions=[];return ok(state.offering)}
    if(path==='/api/v1/demands') return ok({items:[],total:0,page:0,size:20})
    if(path.includes('notifications') || path.includes('matches') || path.includes('collaborations')) return ok({items:[],total:0,page:0,size:20})
    if(path.includes('consent')) return ok([])
    return fail(403,'Outside isolated UI fixture')
  })
  return state
}

test('owner creates a read-only invitation, shows a capability-only link and revokes it',async({page},info)=>{
  const state=await fixture(page)
  await page.goto('/enterprise/team')
  await expect(page.getByRole('heading',{name:'企业团队',exact:true})).toBeVisible()
  await page.getByLabel('成员统一认证账号名').fill('team.user')
  await page.getByRole('button',{name:'创建成员邀请'}).click()
  const link=await page.getByLabel('仅本次显示的成员邀请链接').inputValue()
  expect(link).toBe('http://127.0.0.1:18188/join#invite='+'t'.repeat(43))
  await expect(page.getByText('待成员确认 · 普通成员只读权限')).toBeVisible()
  await page.screenshot({path:info.outputPath('enterprise-team.png'),fullPage:true})
  page.once('dialog',dialog=>dialog.accept())
  await page.getByRole('button',{name:'撤销邀请',exact:true}).click()
  await expect(page.getByText('已撤销 · 普通成员只读权限')).toBeVisible()
  await expect(page.getByLabel('仅本次显示的成员邀请链接')).toHaveCount(0)
  expect(state.errors).toEqual([])
})

test('suspension requires a reason and stale versions fail without false success',async({page})=>{
  const state=await fixture(page);state.conflict=true
  await page.goto('/enterprise/team')
  await page.getByRole('button',{name:'停用成员',exact:true}).click()
  const dialog=page.getByRole('dialog',{name:'确认停用成员'})
  await expect(dialog.getByRole('button',{name:'确认停用',exact:true})).toBeDisabled()
  await dialog.getByLabel('停用原因').fill('测试离职')
  await dialog.getByRole('button',{name:'确认停用',exact:true}).click()
  await expect(dialog.getByRole('alert')).toContainText('成员权限已更新')
  expect(state.member.status).toBe('ACTIVE')
  await dialog.getByRole('button',{name:'取消',exact:true}).click()
  state.conflict=false
  await page.getByRole('button',{name:'刷新团队'}).click()
  await page.getByRole('button',{name:'停用成员',exact:true}).click()
  await page.getByLabel('停用原因').fill('测试离职')
  await page.getByRole('button',{name:'确认停用',exact:true}).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByText('existing.member · 已停用')).toBeVisible()
  expect(state.errors).toEqual([])
})

test('own catalog filters on the server and retains draft and submit actions',async({page},info)=>{
  const state=await fixture(page)
  await page.goto('/enterprise/catalog')
  await expect(page.getByRole('heading',{name:'我的供需',exact:true})).toBeVisible()
  await expect(page.getByRole('heading',{name:'本企业测试产品'})).toBeVisible()
  for(const path of state.requests.filter(path=>path.startsWith('/api/v1/offerings?')||path.startsWith('/api/v1/demands?')))expect(path).toContain('ownOnly=true')
  await page.getByRole('button',{name:'提交审核',exact:true}).click()
  await expect(page.getByRole('button',{name:'提交审核',exact:true})).toHaveCount(0)
  expect(state.offering.status).toBe('PENDING_REVIEW')
  await page.screenshot({path:info.outputPath('my-catalog.png'),fullPage:true})
  expect(state.errors).toEqual([])
})

test('ordinary member has read-only catalog and cannot open team administration',async({page})=>{
  const state=await fixture(page,'ENTERPRISE_MEMBER')
  await page.goto('/enterprise/catalog')
  await expect(page.getByRole('heading',{name:'本企业测试产品'})).toBeVisible()
  await expect(page.getByRole('button',{name:/新建产品|发布需求|提交审核/})).toHaveCount(0)
  await expect(page.getByRole('link',{name:'企业团队',exact:true})).toHaveCount(0)
  await page.goto('/enterprise/team')
  await expect(page).toHaveURL(/\/enterprise$/)
  expect(state.requests.some(path=>path.includes('/team/'))).toBe(false)
  expect(state.writes).toEqual([])
})

test('cooperation entry switches between scoped opportunities and project progress',async({page})=>{
  const state=await fixture(page,'ENTERPRISE_MEMBER')
  await page.goto('/enterprise/cooperation')
  await expect(page.getByRole('heading',{name:'我的合作',exact:true})).toBeVisible()
  await expect(page.getByRole('heading',{name:'生态匹配',exact:true})).toBeVisible()
  await page.getByRole('link',{name:'协作进展',exact:true}).click()
  await expect(page.getByRole('heading',{name:'协作事项',exact:true})).toBeVisible()
  await expect(page).toHaveURL(/view=projects/)
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('team controls fit a narrow viewport without horizontal overflow',async({page},info)=>{
  await page.setViewportSize({width:390,height:844});const state=await fixture(page)
  await page.goto('/enterprise/team')
  await expect(page.getByRole('button',{name:'创建成员邀请'})).toBeDisabled()
  await page.getByLabel('成员统一认证账号名').fill('team.user')
  await expect(page.getByRole('button',{name:'创建成员邀请'})).toBeEnabled()
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBe(true)
  await page.screenshot({path:info.outputPath('enterprise-team-mobile.png'),fullPage:true})
  expect(state.errors).toEqual([])
})
