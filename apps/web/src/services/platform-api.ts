import { associationDashboard, collaborations, enterpriseDashboard, matches, members, policies } from '../mocks/data'
import type { Collaboration, DashboardData, EcosystemMatch, EnterpriseDashboardData, MemberEnterprise, Policy } from '../types/domain'
import { request } from './http'

const mock = <T>(data: T) => async () => {
  await new Promise((resolve) => window.setTimeout(resolve, 120))
  return data
}

export const platformApi = {
  associationDashboard: () => request<DashboardData>('/dashboards/association', {}, mock(associationDashboard)),
  enterpriseDashboard: () => request<EnterpriseDashboardData>('/dashboards/enterprise', {}, mock(enterpriseDashboard)),
  members: () => request<MemberEnterprise[]>('/members', {}, mock(members)),
  policies: () => request<Policy[]>('/policies', {}, mock(policies)),
  matches: () => request<EcosystemMatch[]>('/matches', {}, mock(matches)),
  collaborations: () => request<Collaboration[]>('/collaborations', {}, mock(collaborations)),
}
