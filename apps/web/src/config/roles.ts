import type { UserRole } from '../types/domain'

export const roleLabels: Record<UserRole, string> = {
  SYSTEM_ADMIN: '系统管理员',
  ASSOCIATION_ADMIN: '协会管理员',
  ASSOCIATION_OPERATOR: '协会运营人员',
  ENTERPRISE_ADMIN: '企业管理员',
  ENTERPRISE_MEMBER: '企业业务人员',
}

export const roleDescriptions: Record<UserRole, string> = {
  SYSTEM_ADMIN: '平台配置、全局数据与安全审计',
  ASSOCIATION_ADMIN: '协会运营、会员审核与生态统筹',
  ASSOCIATION_OPERATOR: '内容维护、撮合跟进与任务办理',
  ENTERPRISE_ADMIN: '企业资料、团队与供需信息管理',
  ENTERPRISE_MEMBER: '业务信息维护、政策与商机协同',
}

export const associationRoles: UserRole[] = [
  'SYSTEM_ADMIN',
  'ASSOCIATION_ADMIN',
  'ASSOCIATION_OPERATOR',
]

export const enterpriseRoles: UserRole[] = ['ENTERPRISE_ADMIN', 'ENTERPRISE_MEMBER']

export const allRoles: UserRole[] = [...associationRoles, ...enterpriseRoles]

export function hasAnyRole(role: UserRole, roles?: readonly UserRole[]): boolean {
  return Boolean(roles?.includes(role))
}

export function defaultRouteForRole(role: UserRole): string {
  return enterpriseRoles.includes(role) ? '/enterprise' : '/association'
}
