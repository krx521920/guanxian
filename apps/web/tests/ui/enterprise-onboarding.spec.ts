import { expect, test, type Page } from '@playwright/test'
import type { ProfileWorkflow } from '../../src/services/profile-workflow'

const associationId='10000000-0000-4000-8000-000000000001', enterpriseId='20000000-0000-4000-8000-000000000001'
const authority='http://127.0.0.1:18188/identity/realms/entry-tests'
const token='t'.repeat(43)
type FixtureRole='SYSTEM_ADMIN'|'ENTERPRISE_ADMIN'|'ENTERPRISE_MEMBER'|'pending'
function scenario() {
  return {
    invitation: { id:'30000000-0000-4000-8000-000000000001', enterpriseId, enterpriseName:'虚构·企业接入验证公司', associationName:'虚构·验证协会',
      username:'owner.user', status:'ISSUED', version:0, expiresAt:'2099-09-08T12:00:00Z', createdAt:'2026-09-05T12:00:00Z',
      claimantName:null as string|null, claimantSubject:null as string|null, reviewNote:null as string|null },
    created:false, conflict:false, writes:[] as unknown[], requests:[] as string[], errors:[] as string[],
    workflow:null as ProfileWorkflow|null,
    profile: { id:enterpriseId, associationId, name:'虚构·企业接入验证公司', unifiedSocialCreditCode:'TEST-ONLY-0001', category:'技术服务',
      address:'虚构地址',contactName:'测试负责人', contactPhone:'测试号码', contactEmail:'owner@example.test', introduction:'原有企业简介',
      capabilities:['管线探测'],products:['探测设备'],services:[],applicationScenarios:[],cooperationNeeds:[],visibility:'MEMBERS',status:'ACTIVE',version:0,
      createdAt:'2026-09-05T12:00:00Z',updatedAt:'2026-09-05T12:00:00Z',deletedAt:null },
  }
}
async function fixture(page:Page, role?:FixtureRole, state= scenario()) {
  state.workflow ||= { official: structuredClone(state.profile), draft:null, approved:null, publication:null,
    version:0,published:false,canEdit:true,canReview:false,canConsent:false,canPublish:false,canWithdraw:true } as ProfileWorkflow
  page.on('pageerror',e=>state.errors.push(e.message))
  await page.addInitScript(({role,authority})=>{
    if(role) sessionStorage.setItem(`oidc.user:${authority}:entry-tests`,JSON.stringify({ access_token:'fixture-only-token',token_type:'Bearer',scope:'openid',
      expires_at:Math.floor(Date.now()/1000)+3600,profile:{sub:role==='SYSTEM_ADMIN'?'reviewer':'owner-subject'} }))
  },{role,authority})
  await page.route('**/api/v1/**',async route=>{
    const request=route.request(),path=new URL(request.url()).pathname
    state.requests.push(path)
    const ok=(data:unknown,headers:Record<string,string>={})=>route.fulfill({json:{code:'OK',data},headers})
    const fail=(status:number,message:string)=>route.fulfill({status,json:{code:'FIXTURE_DENIED',message}})
    const effective=role==='pending'&&state.invitation.status==='APPROVED'?'ENTERPRISE_ADMIN':role
    if(path==='/api/v1/public/enterprises') {
      expect(request.headers()['authorization']).toBeUndefined()
      return ok(state.workflow?.published?[state.workflow.publication]:[])
    }
    if(path.startsWith('/api/v1/enterprise-profiles/')) {
      const w=state.workflow!
      if(request.method()!=='GET') {
        expect(request.headers()['if-match']).toBe(`"${w.version}"`)
        const action=path.split('/').at(-1),body=request.postDataJSON()
        if(action==='draft') {
          state.writes.push(body.content)
          if(state.conflict) return fail(412,'资料版本冲突')
          w.draft={id:'revision',baseVersion:w.official.version,content:body.content,status:'DRAFT',editors:['owner-subject'],submittedBy:null,reviewNote:w.draft?.reviewNote||null,reviewedBy:null,submittedAt:null,reviewedAt:null}
        }
        if(action==='submit') {w.draft!.status='SUBMITTED';w.draft!.submittedBy='owner-subject'}
        if(action==='review') {
          w.draft!.reviewNote=body.note;w.draft!.status=body.approve?'APPROVED':'REJECTED'
          if(body.approve) { Object.assign(state.profile,w.draft!.content,{version:state.profile.version+1,status:'ACTIVE'});w.official=structuredClone(state.profile) as ProfileWorkflow['official'];w.approved={id:'revision',profile:structuredClone(w.official),consentedAt:null,approvedAt:'2026-09-05T12:00:00Z'} }
        }
        if(action==='consent') w.approved!.consentedAt='2026-09-05T12:01:00Z'
        if(action==='publish') {const p=w.approved!.profile;w.publication={id:p.id,name:p.name,category:p.category,introduction:p.introduction,capabilities:p.capabilities,products:p.products,services:p.services,applicationScenarios:p.applicationScenarios,publicationId:'publication',publishedAt:'2026-09-05T12:02:00Z'};w.published=true}
        if(action==='withdraw') {w.published=false;w.approved!.consentedAt=null}
        w.version++
      }
      return ok({...w,canEdit:w.draft?.status!=='SUBMITTED',canReview:effective==='SYSTEM_ADMIN'&&w.draft?.status==='SUBMITTED',canConsent:effective==='ENTERPRISE_ADMIN'&&!!w.approved,canPublish:effective==='SYSTEM_ADMIN'&&!!w.approved?.consentedAt})
    }
    if(path==='/api/v1/enterprise-profile-reviews') return ok(state.workflow?.draft?.status==='SUBMITTED'?[{id:enterpriseId,name:state.profile.name,submittedAt:'2026-09-05T12:00:00Z'}]:[])
    if(path===`/api/v1/members/${enterpriseId}`) return ok(state.profile,{ETag:`"${state.profile.version}"`})
    if(path==='/api/v1/users/me') {
      if(effective==='pending') return fail(403,'尚未绑定')
      return ok({subject:effective==='SYSTEM_ADMIN'?'reviewer':'owner-subject',username:effective==='SYSTEM_ADMIN'?'admin':'owner.user',displayName:effective==='SYSTEM_ADMIN'?'测试管理员':'测试负责人',
        roles:[effective],permissions:['MEMBER_READ','ENTERPRISE_WRITE','ACCESS_BINDING_WRITE'],associationId,enterpriseId:effective?.startsWith('ENTERPRISE')?enterpriseId:null,organization:'虚构·企业接入验证公司'})
    }
    if(path==='/api/v1/onboarding/session') return ok({subject:'owner-subject',username:'owner.user',displayName:'测试负责人'})
    if(path==='/api/v1/onboarding/preview') {
      if(request.postDataJSON().token!==token) return fail(404,'邀请不可用、已过期或与当前账号不符')
      return ok(state.invitation)
    }
    if(path==='/api/v1/onboarding/claim') {
      expect(request.postDataJSON()).toEqual({token,confirmed:true})
      state.invitation.status='CLAIMED';state.invitation.version++;state.invitation.claimantName='测试负责人';state.invitation.claimantSubject='owner-subject'
      return ok(state.invitation)
    }
    if(path==='/api/v1/onboarding/invitations') return ok(state.invitation.status==='ISSUED'?[]:[state.invitation])
    if(path==='/api/v1/enterprise-invitations'&&request.method()==='POST') {
      expect(request.postDataJSON()).toEqual({enterpriseId,username:'owner.user'})
      state.created=true;return ok({invitation:state.invitation,token})
    }
    if(path==='/api/v1/enterprise-invitations') return ok({items:state.created?[state.invitation]:[],total:state.created?1:0})
    if(path.endsWith('/review')) {
      expect(request.headers()['if-match']).toBe('"1"')
      state.invitation.status='APPROVED';state.invitation.version++;state.invitation.reviewNote=request.postDataJSON().note
      return ok(state.invitation)
    }
    if(path==='/api/v1/my-enterprise') {
      if(request.method()==='PUT') {
        state.writes.push(request.postDataJSON());expect(request.headers()['if-match']).toBe('"0"')
        if(state.conflict) return fail(412,'资料版本冲突')
        Object.assign(state.profile,request.postDataJSON(),{version:1,status:'PENDING_REVIEW'})
      }
      return ok({profile:state.profile,canEdit:effective==='ENTERPRISE_ADMIN'},{ETag:`"${state.profile.version}"`})
    }
    if(path==='/api/v1/members/page') return ok({items:[{id:enterpriseId,name:state.profile.name,shortName:'虚构',status:'已认证',canEdit:true,products:[],scenes:[],version:0,completeness:80}],total:1,page:0,size:20})
    if(path==='/api/v1/dashboards/enterprise') return ok({completeness:70,metrics:[],matches:[],recommendedPolicies:[]})
    if(path.includes('notifications')) return ok({items:[],total:0,page:0,size:20})
    if(path==='/api/v1/system-context/associations') return ok([{id:associationId,name:'虚构·验证协会'}])
    if(path==='/api/v1/system-context/enterprises') return ok([{id:enterpriseId,associationId,name:state.profile.name}])
    return fail(403,'Outside local browser fixture')
  })
  return state
}

test('administrator invites, owner confirms, human approval opens my enterprise and review submission',async({page,browser},info)=>{
  const state=await fixture(page,'SYSTEM_ADMIN')
  await page.goto('/operations/invitations')
  await page.getByRole('button',{name:'查找企业',exact:true}).click()
  await page.getByRole('button',{name:'虚构·企业接入验证公司 选择'}).click()
  await page.getByLabel('负责人统一认证账号名').fill('owner.user')
  await page.getByRole('button',{name:'创建 72 小时有效邀请'}).click()
  const url=await page.getByLabel('仅本次显示的邀请链接').inputValue()
  expect(url).toContain('/join#invite=')
  const ownerContext=await browser.newContext({baseURL:'http://127.0.0.1:18188',locale:'zh-CN'})
  try {
    const ownerPage=await ownerContext.newPage();await fixture(ownerPage,'pending',state)
    await ownerPage.goto(url)
    await expect(ownerPage.getByRole('heading',{name:'连接账号与您的企业'})).toBeVisible()
    await expect(ownerPage).toHaveURL(/\/join$/)
    await expect(ownerPage.locator('.app-shell')).toHaveCount(0)
    await expect(ownerPage.getByRole('button',{name:'确认并提交绑定申请'})).toBeDisabled()
    await ownerPage.getByRole('checkbox').check()
    await ownerPage.getByRole('button',{name:'确认并提交绑定申请'}).click()
    await expect(ownerPage.getByText('待管理员核验',{exact:true})).toBeVisible()
    await page.getByRole('button',{name:'刷新',exact:true}).click()
    await page.getByRole('button',{name:'核验绑定',exact:true}).click()
    await expect(page.getByRole('button',{name:'批准绑定'})).toBeDisabled()
    await page.getByLabel('核验依据 / 退回原因').fill('已通过协会留存电话核验负责人授权。')
    await page.getByRole('checkbox').check()
    await page.evaluate(()=>window.scrollTo(0,0))
    await page.screenshot({path:info.outputPath('administrator-review.png'),fullPage:true})
    await page.getByRole('button',{name:'批准绑定'}).click()
    await expect(page.getByText('指定账号：owner.user · 已开通')).toBeVisible()
    await ownerPage.getByRole('button',{name:'重新检查权限 / 进入我的企业'}).click()
    await expect(ownerPage).toHaveURL(/\/enterprise$/)
    await ownerPage.getByRole('link',{name:'维护本企业资料',exact:true}).click()
    await expect(ownerPage.getByText('资料草稿与审核发布',{exact:true})).toBeVisible()
    await ownerPage.getByLabel('企业简介').fill('负责人维护的新简介，仅作为本地测试。')
    await ownerPage.getByRole('button',{name:'保存草稿',exact:true}).click()
    await expect(ownerPage.getByRole('status')).toContainText('草稿已保存')
    expect(state.profile.introduction).toBe('原有企业简介')
    await ownerPage.getByRole('button',{name:'提交协会审核',exact:true}).click()
    await expect(ownerPage.getByText('待审核 · 版本已冻结',{exact:true})).toBeVisible()
    await expect(ownerPage.getByLabel('企业简介')).toBeDisabled()
    await ownerPage.evaluate(()=>window.scrollTo(0,0))
    await ownerPage.screenshot({path:info.outputPath('my-enterprise.png'),fullPage:true})
    expect(state.writes).toHaveLength(1)
    expect(state.writes[0]).toMatchObject({name:'虚构·企业接入验证公司',unifiedSocialCreditCode:'TEST-ONLY-0001',associationId,status:'ACTIVE',visibility:'MEMBERS'})
    expect(state.errors).toEqual([])
  } finally { await ownerContext.close() }
})

test('anonymous invitation does not query business data and its capability is removed from URL',async({page})=>{
  const state=await fixture(page)
  await page.goto('/join#invite='+token)
  await expect(page.getByRole('button',{name:'登录并确认邀请'})).toBeVisible()
  await expect(page).toHaveURL(/\/join$/)
  expect(state.requests).toEqual([])
  await page.getByRole('link',{name:'返回公开平台'}).click()
  await expect(page).toHaveURL(/\/public$/)
})

test('a wrong invitation and pending user cannot access the enterprise form',async({page})=>{
  const state=await fixture(page,'pending')
  await page.goto('/join#invite='+'x'.repeat(43))
  await expect(page.getByRole('alert')).toContainText('邀请不可用')
  await expect(page.getByRole('button',{name:'确认并提交绑定申请'})).toHaveCount(0)
  await page.goto('/enterprise/profile')
  await expect(page).toHaveURL(/\/join$/)
  expect(state.requests).not.toContain('/api/v1/my-enterprise')
})

test('profile conflict preserves local edits and does not silently overwrite newer data',async({page})=>{
  const state=await fixture(page,'ENTERPRISE_ADMIN');state.conflict=true
  await page.goto('/enterprise/profile')
  await page.getByLabel('企业简介').fill('未提交的负责人修改')
  await page.getByRole('button',{name:'保存草稿',exact:true}).click()
  await expect(page.getByRole('alert')).toContainText('未覆盖他人的修改')
  await expect(page.getByLabel('企业简介')).toHaveValue('未提交的负责人修改')
  expect(state.writes).toHaveLength(1)
  expect(state.profile.introduction).toBe('原有企业简介')
})

test('enterprise staff receive a read-only mobile profile without contact inputs',async({page},info)=>{
  const state=await fixture(page,'ENTERPRISE_MEMBER')
  await page.setViewportSize({width:390,height:844})
  await page.goto('/enterprise/profile')
  await expect(page.getByLabel('企业简介')).toBeDisabled()
  await expect(page.getByLabel('联系电话')).toHaveCount(0)
  await expect(page.getByRole('button',{name:'保存并提交协会审核'})).toHaveCount(0)
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.screenshot({path:info.outputPath('my-enterprise-mobile-readonly.png'),fullPage:true})
  expect(state.writes).toEqual([])
})

test('draft rejection, resubmission, independent approval, consent, publication and withdrawal are separate UI steps',async({page,browser},info)=>{
  const state=await fixture(page,'ENTERPRISE_ADMIN')
  const adminContext=await browser.newContext({baseURL:'http://127.0.0.1:18188',locale:'zh-CN'})
  const guestContext=await browser.newContext({baseURL:'http://127.0.0.1:18188',locale:'zh-CN'})
  try {
    const admin=await adminContext.newPage();await fixture(admin,'SYSTEM_ADMIN',state)
    const guest=await guestContext.newPage();await fixture(guest,undefined,state)
    await page.goto('/enterprise/profile')
    await page.getByLabel('企业简介').fill('用于审核的业务简介')
    await page.getByRole('button',{name:'保存草稿',exact:true}).click()
    await expect(page.getByText('草稿 · 尚未提交',{exact:true})).toBeVisible()
    await guest.goto('/public');await expect(guest.getByRole('heading',{name:'暂无符合条件的已发布企业'})).toBeVisible()
    await page.getByRole('button',{name:'提交协会审核',exact:true}).click()
    await expect(page.getByLabel('企业简介')).toBeDisabled()
    await admin.goto('/members');await admin.getByRole('button',{name:'查看资料待审核队列'}).click()
    await admin.getByRole('link',{name:'虚构·企业接入验证公司 · 核对草稿与差异 →'}).click()
    const panel=admin.getByRole('region',{name:'资料草稿与审核发布'})
    await expect(panel.getByRole('button',{name:'退回修改',exact:true})).toBeDisabled()
    await expect(panel.getByRole('table')).toContainText('原有企业简介')
    await expect(panel.getByRole('table')).toContainText('用于审核的业务简介')
    await panel.getByLabel('审核说明／退回原因 *').fill('请补充实际服务场景')
    await panel.getByRole('button',{name:'退回修改',exact:true}).click()
    await page.getByRole('button',{name:'重新加载草稿'}).click()
    await expect(page.getByLabel('审核反馈')).toContainText('请补充实际服务场景')
    await page.getByLabel('企业简介').fill('已核对的管线探测服务简介')
    await page.getByRole('button',{name:'保存草稿',exact:true}).click()
    await expect(page.getByLabel('审核反馈')).toContainText('请补充实际服务场景')
    await page.getByRole('button',{name:'提交协会审核',exact:true}).click()
    await panel.getByRole('button',{name:'重新加载草稿'}).click()
    await panel.getByLabel('审核说明／退回原因 *').fill('已核验业务内容，不含私人联系信息')
    await panel.getByRole('button',{name:'审核通过并内部生效'}).click()
    await expect(panel.getByText('审核通过 · 内部已生效',{exact:true})).toBeVisible()
    await guest.reload();await expect(guest.getByRole('heading',{name:'暂无符合条件的已发布企业'})).toBeVisible()
    await page.getByRole('button',{name:'重新加载草稿'}).click()
    await expect(page.getByRole('button',{name:'确认本版本公开授权'})).toBeDisabled()
    await page.getByRole('checkbox',{name:'我代表本企业确认上述审核版本允许向所有游客公开，并已检查自由文本。'}).check()
    await page.getByRole('button',{name:'确认本版本公开授权'}).click()
    await expect(page.getByRole('status')).toContainText('已记录')
    await panel.getByRole('button',{name:'重新加载草稿'}).click()
    admin.once('dialog',dialog=>dialog.accept())
    await panel.getByRole('button',{name:'发布已审核版本',exact:true}).click()
    await expect(panel.getByRole('status')).toContainText('已发布')
    await guest.reload();await expect(guest.getByRole('heading',{name:state.profile.name})).toBeVisible()
    await expect(guest.getByText('已核对的管线探测服务简介',{exact:true})).toBeVisible()
    await expect(guest.getByText('测试号码',{exact:true})).toHaveCount(0)
    await page.getByRole('button',{name:'重新加载草稿'}).click()
    await page.getByLabel('企业简介').fill('新草稿不能提前展示给游客')
    await page.getByRole('button',{name:'保存草稿',exact:true}).click()
    await guest.reload();await expect(guest.getByText('已核对的管线探测服务简介',{exact:true})).toBeVisible()
    await expect(guest.getByText('新草稿不能提前展示给游客',{exact:true})).toHaveCount(0)
    await page.getByLabel('企业简介').fill('未保存的内容也不能阻止紧急撤回')
    await page.getByLabel('撤回／下架原因 *').fill('企业决定暂不展示')
    await page.getByRole('button',{name:'立即撤回公开展示'}).click()
    await expect(page.getByLabel('企业简介')).toHaveValue('未保存的内容也不能阻止紧急撤回')
    await guest.reload();await expect(guest.getByRole('heading',{name:'暂无符合条件的已发布企业'})).toBeVisible()
    await page.evaluate(()=>window.scrollTo(0,0))
    await page.screenshot({path:info.outputPath('profile-workflow-owner.png'),fullPage:true})
    expect(state.errors).toEqual([])
  } finally {await adminContext.close();await guestContext.close()}
})

test('mobile owner draft exposes feedback and separate submit action without horizontal overflow',async({page},info)=>{
  const state=await fixture(page,'ENTERPRISE_ADMIN')
  await page.setViewportSize({width:390,height:844})
  await page.goto('/enterprise/profile');await page.getByLabel('企业简介').fill('移动端保存的草稿')
  await page.getByRole('button',{name:'保存草稿',exact:true}).click()
  await expect(page.getByRole('button',{name:'提交协会审核',exact:true})).toBeEnabled()
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  expect(state.profile.introduction).toBe('原有企业简介')
  await page.evaluate(()=>window.scrollTo(0,0));await page.screenshot({path:info.outputPath('profile-workflow-mobile.png'),fullPage:true})
})
