import type { UserRole } from '../types/domain'
import { allRoles, associationRoles, enterpriseRoles } from './roles'

export type NavIcon = 'dashboard' | 'enterprise' | 'policy' | 'match' | 'task'

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
  { label: '生态匹配', to: '/matching', icon: 'match', roles: allRoles, badge: '6' },
  { label: '协作事项', to: '/collaborations', icon: 'task', roles: allRoles, badge: '3' },
]

export function navigationForRole(role: UserRole): NavItem[] {
  return navigation.filter((item) => item.roles.includes(role))
}
