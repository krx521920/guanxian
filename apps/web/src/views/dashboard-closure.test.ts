import { describe, expect, it } from 'vitest'
import appShell from '../layouts/AppShell.vue?raw'
import associationDashboard from './AssociationDashboard.vue?raw'
import enterpriseDashboard from './EnterpriseDashboard.vue?raw'
import loginView from './LoginView.vue?raw'
import membersView from './MembersView.vue?raw'

describe('dashboard and member UI closure', () => {
  it('keeps a persistent return-to-workspace control in the application header', () => {
    expect(appShell).toContain("const workspaceHome = computed(() => auth.user.value ? defaultRouteForRole(auth.user.value.role) : '/')")
    expect(appShell).toContain('class="topbar-back-button"')
    expect(appShell).toContain(':to="workspaceHome"')
    expect(appShell).toContain('aria-label="返回工作台"')
  })

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

  it('keeps member paging, retry and empty results tied to the real server response', () => {
    expect(membersView).toContain('const memberLoadGate = createLatestRequestGate()')
    expect(membersView).toContain('if (!memberLoadGate.isCurrent(requestEpoch)) return')
    expect(membersView).toContain('@retry="load"')
    expect(membersView).toContain('<PaginationBar :page="page" :size="size" :total="total"')
    expect(membersView).toContain("'暂无会员企业'")
    expect(membersView).toContain('调查表批量导入建立真实企业资料')
    expect(membersView).not.toContain('paginated')
    expect(membersView).not.toContain('<span v-else class="table-muted">查看</span>')
  })
})
