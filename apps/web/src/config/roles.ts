import type { UserRole } from '../types/domain'

export const roleLabels: Record<UserRole, string> = {
  SYSTEM_ADMIN: '系统管理员',
  ASSOCIATION_ADMIN: '协会管理员',
  ASSOCIATION_OPERATOR: '协会运营人员',
  ENTERPRISE_ADMIN: '企业管理员',
  ENTERPRISE_MEMBER: '企业业务人员',
  OBSERVER: '只读观察员',
}

export const roleDescriptions: Record<UserRole, string> = {
  SYSTEM_ADMIN: '平台配置、全局数据与安全审计',
  ASSOCIATION_ADMIN: '协会运营、会员审核与生态统筹',
  ASSOCIATION_OPERATOR: '内容维护、撮合跟进与任务办理',
  ENTERPRISE_ADMIN: '企业资料、团队与供需信息管理',
  ENTERPRISE_MEMBER: '查看企业信息、政策、匹配与协作进度',
  OBSERVER: '只读查看会员资料与政策标准',
}

export const associationRoles: UserRole[] = [
  'SYSTEM_ADMIN',
  'ASSOCIATION_ADMIN',
  'ASSOCIATION_OPERATOR',
]

export const enterpriseRoles: UserRole[] = ['ENTERPRISE_ADMIN', 'ENTERPRISE_MEMBER']

export const observerRoles: UserRole[] = ['OBSERVER']

export const workspaceRoles: UserRole[] = [...associationRoles, ...enterpriseRoles]

export const allRoles: UserRole[] = [...workspaceRoles, ...observerRoles]

export function hasAnyRole(role: UserRole, roles?: readonly UserRole[]): boolean {
  return Boolean(roles?.includes(role))
}

export function defaultRouteForRole(role: UserRole): string {
  if (observerRoles.includes(role)) return '/members'
  return enterpriseRoles.includes(role) ? '/enterprise' : '/association'
}
