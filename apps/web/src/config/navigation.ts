import type { UserRole } from '../types/domain'
import { allRoles, associationRoles, enterpriseRoles, workspaceRoles } from './roles'

export type NavIcon = 'dashboard' | 'ecosystem' | 'enterprise' | 'policy' | 'match' | 'task'

export interface NavItem {
  label: string
  to: string
  icon: NavIcon
  roles: UserRole[]
  badge?: string
}

export const navigation: NavItem[] = [
  { label: '协会工作台', to: '/association', icon: 'dashboard', roles: associationRoles },
  { label: '企业工作台', to: '/enterprise', icon: 'dashboard', roles: enterpriseRoles },
  { label: '会员企业', to: '/members', icon: 'enterprise', roles: allRoles },
  { label: '政策标准', to: '/policies', icon: 'policy', roles: allRoles },
  { label: '产品与需求', to: '/ecosystem', icon: 'ecosystem', roles: workspaceRoles },
  { label: '生态匹配', to: '/matching', icon: 'match', roles: workspaceRoles },
  { label: '协作事项', to: '/collaborations', icon: 'task', roles: workspaceRoles },
  { label: '资料附件', to: '/attachments', icon: 'enterprise', roles: workspaceRoles },
  { label: '友好协会', to: '/federation', icon: 'match', roles: associationRoles },
  { label: '审计与账号', to: '/operations', icon: 'task', roles: ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN'] },
]

export function navigationForRole(role: UserRole): NavItem[] {
  return navigation.filter((item) => item.roles.includes(role))
}
