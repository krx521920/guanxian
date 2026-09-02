import { describe, expect, it } from 'vitest'
import associationDashboard from './AssociationDashboard.vue?raw'
import enterpriseDashboard from './EnterpriseDashboard.vue?raw'
import loginView from './LoginView.vue?raw'
import membersView from './MembersView.vue?raw'

describe('dashboard and member UI closure', () => {
  it('keeps every association dashboard action connected to a route or reload', () => {
    expect(associationDashboard).toContain('to="/members"')
    expect(associationDashboard).toContain('to="/collaborations"')
    expect(associationDashboard).toContain('to="/ecosystem"')
    expect(associationDashboard).toContain('@click="load"')
    expect(associationDashboard).not.toMatch(/associationSample|能力缺口提示|<span>AI<\/span>|•••/)
  })

  it('derives enterprise profile wording from real completeness and exposes real routes', () => {
    expect(enterpriseDashboard).toContain("const completeness = data.value?.completeness ?? 0")
    expect(enterpriseDashboard).toContain('to="/members"')
    expect(enterpriseDashboard).toContain('to="/matching"')
    expect(enterpriseDashboard).toContain('v-if="data.matches.length === 0"')
    expect(enterpriseDashboard).toContain('v-if="data.recommendedPolicies.length === 0"')
    expect(enterpriseDashboard).not.toMatch(/具备匹配条件|补充 2 项|AI 匹配|智能筛选/)
  })

  it('does not market unfinished AI capability on the login page', () => {
    expect(loginView).toContain('可信业务数据支持行业协作')
    expect(loginView).toContain('规则匹配')
    expect(loginView).not.toMatch(/\bAI\b|智能匹配|生态中枢/)
  })

  it('does not claim a member list refresh before checking its result', () => {
    const loadingMessage = membersView.indexOf('正在刷新会员列表')
    const loadCall = membersView.indexOf('await load()', loadingMessage)
    const successMessage = membersView.indexOf('会员列表已刷新', loadCall)
    expect(loadingMessage).toBeGreaterThan(-1)
    expect(loadCall).toBeGreaterThan(loadingMessage)
    expect(successMessage).toBeGreaterThan(loadCall)
    expect(membersView).toContain('会员列表刷新失败')
    expect(membersView).toContain('paginated.length === 0')
    expect(membersView).not.toContain('<span v-else class="table-muted">查看</span>')
  })
})
